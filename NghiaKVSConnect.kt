package jp.co.secom.design.cloudcam.aws.utils

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.services.kinesisvideo.AWSKinesisVideoClient
import com.amazonaws.services.kinesisvideo.model.ChannelRole
import com.amazonaws.services.kinesisvideo.model.DescribeSignalingChannelRequest
import com.amazonaws.services.kinesisvideo.model.GetSignalingChannelEndpointRequest
import com.amazonaws.services.kinesisvideo.model.ResourceEndpointListItem
import com.amazonaws.services.kinesisvideo.model.ResourceNotFoundException
import com.amazonaws.services.kinesisvideo.model.SingleMasterChannelEndpointConfiguration
import com.amazonaws.services.kinesisvideosignaling.AWSKinesisVideoSignalingClient
import com.amazonaws.services.kinesisvideosignaling.model.GetIceServerConfigRequest
import com.amazonaws.services.kinesisvideosignaling.model.IceServer
import jp.co.secom.design.cloudcam.aws.signaling.SignalingListener
import jp.co.secom.design.cloudcam.aws.signaling.model.Event
import jp.co.secom.design.cloudcam.aws.signaling.model.Message
import jp.co.secom.design.cloudcam.aws.signaling.tyrus.SignalingServiceWebSocketClient
import jp.co.secom.design.cloudcam.aws.webrtc.KinesisVideoPeerConnection
import jp.co.secom.design.cloudcam.aws.webrtc.KinesisVideoSdpObserver
import jp.co.secom.design.cloudcam.core.util.AppLog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.URI
import java.util.LinkedList
import java.util.Queue
import java.util.UUID
import java.util.concurrent.Executors

class ViewKVSConnectNghia(
    var context: Context,
    var peerConnectionFactory: PeerConnectionFactory,
    var videoSource: VideoSource,
    val mAccessKey: String,
    val mSecretKey: String,
    val awsRegionName: String,
    val kvsVideoStreamName: String
) {

    private val mEndpointList = ArrayList<ResourceEndpointListItem>()
    private val mIceServerList = ArrayList<IceServer>()
    private var mChannelArn: String? = null

    private var mClientId: String? = null
    private var mWssEndpoint: String? = null
    private val peerIceServers = ArrayList<PeerConnection.IceServer>()

    // web rtc
    private val AudioTrackID = "KvsAudioTrack"
    private val VideoTrackID = "KvsVideoTrack"
    private val LOCAL_MEDIA_STREAM_LABEL = "KvsLocalMediaStream"

    //Local
    private var localVideoTrack: VideoTrack? = null
    private var audioManager: AudioManager? = null
    private var originalAudioMode = 0
    private var originalSpeakerphoneOn = false
    private var localAudioTrack: AudioTrack? = null
    private var localPeer: PeerConnection? = null
    private var isAudioSent = false

    //Ws
    private var gotException = false
    private var recipientClientId: String? = null
    private var mCreds: AWSCredentials? = null
    private var client: SignalingServiceWebSocketClient? = null

    // Map to keep track of established peer connections by IDs
    private val peerConnectionFoundMap = HashMap<String?, PeerConnection>()

    // Map to keep track of ICE candidates received for a client ID before peer connection is established
    private val pendingIceCandidatesMap = HashMap<String?, Queue<IceCandidate>>()

    companion object {
        private const val TAG = "ViewKVSConnect"
        private const val TIME_OUT_CONNECT = 5000L
    }

    @DelicateCoroutinesApi
    fun connect() {
        GlobalScope.launch {
            // Get Channel Info
            val getError =
                getSignalChannelInfo(awsRegionName, kvsVideoStreamName)

            if (getError != null || mChannelArn.isNullOrEmpty()) {
                // Return error
                AppLog.e(TAG, getError ?: "Channel null")
                return@launch
            }

            try {
                //Init Connection
                initConnection()

                //Init WS Connection
                initWsConnection()
            } catch (ex: Exception) {
                AppLog.e(TAG, "ex: ${ex.message}")
//                kvsListener?.onError(cameraEntity, "Connection error: ${ex.message}")
            }

            if (gotException || !isValidClient()) {
                AppLog.e(TAG, "Connection error to signaling")
            }
        }

    }

    fun disconnect() {
        AppLog.e(TAG, "disconnect")
        audioManager?.mode = originalAudioMode
        audioManager?.isSpeakerphoneOn = originalSpeakerphoneOn

        if (localPeer != null) {
            localPeer?.dispose()
            localPeer = null
        }
        if (client != null) {
            client?.disconnect()
            client = null
        }

        peerConnectionFoundMap.clear()
        pendingIceCandidatesMap.clear()

        localAudioTrack?.dispose()
        localVideoTrack?.dispose()
        localVideoTrack = null

        mEndpointList.clear()
        mIceServerList.clear()

    }

    /*******************************
     * Get Channel Info
     */

    private fun getSignalChannelInfo(region: String, channelName: String): String? {
        val awsKinesisVideoClient: AWSKinesisVideoClient? = try {
            getAwsKinesisVideoClient(region)
        } catch (e: Exception) {
            return "Create client failed with " + e.localizedMessage
        }
        try {
            val describeSignalingChannelResult = awsKinesisVideoClient?.describeSignalingChannel(
                DescribeSignalingChannelRequest()
                    .withChannelName(channelName)
            )
            AppLog.d(
                TAG,
                "Channel ARN is " + describeSignalingChannelResult?.channelInfo?.channelARN
            )
            mChannelArn = describeSignalingChannelResult?.channelInfo?.channelARN
        } catch (e: ResourceNotFoundException) {
            return "Signaling Channel $channelName doesn't exist!"
        } catch (ex: Exception) {
            return "Describe Signaling Channel failed with Exception " + ex.localizedMessage
        }
        try {
            val getSignalingChannelEndpointResult =
                awsKinesisVideoClient?.getSignalingChannelEndpoint(
                    GetSignalingChannelEndpointRequest()
                        .withChannelARN(mChannelArn)
                        .withSingleMasterChannelEndpointConfiguration(
                            SingleMasterChannelEndpointConfiguration()
                                .withProtocols("WSS", "HTTPS")
                                .withRole(ChannelRole.VIEWER)
                        )
                )
            AppLog.d(TAG, "Endpoints $getSignalingChannelEndpointResult")
            val resourceEndpointList = getSignalingChannelEndpointResult?.resourceEndpointList
            if (resourceEndpointList != null) {
                mEndpointList.addAll(resourceEndpointList)
            }
        } catch (e: Exception) {
            return "Get Signaling Endpoint failed with Exception " + e.localizedMessage
        }
        var dataEndpoint: String? = null
        for (endpoint in mEndpointList) {
            if (endpoint.protocol == "HTTPS") {
                dataEndpoint = endpoint.resourceEndpoint
            }
        }
        try {
            val awsKinesisVideoSignalingClient =
                getAwsKinesisVideoSignalingClient(region, dataEndpoint)
            val getIceServerConfigResult = awsKinesisVideoSignalingClient?.getIceServerConfig(
                GetIceServerConfigRequest().withChannelARN(mChannelArn)
                    .withClientId(ChannelRole.VIEWER.name)
            )
            val iceServerList = getIceServerConfigResult?.iceServerList
            if (iceServerList != null) {
                mIceServerList.addAll(getIceServerConfigResult.iceServerList)
            }
        } catch (e: Exception) {
            return "Get Ice Server Config failed with Exception " + e.localizedMessage
        }
        return null
    }

    private fun getAwsKinesisVideoSignalingClient(
        region: String,
        endpoint: String?
    ): AWSKinesisVideoSignalingClient? {
        val client = AWSKinesisVideoSignalingClient(getBasicCredentials())
        client.setRegion(Region.getRegion(region))
        client.signerRegionOverride = region
        client.setServiceNameIntern("kinesisvideo")
        client.endpoint = endpoint
        return client
    }

    private fun getBasicCredentials(): BasicAWSCredentials {
        return BasicAWSCredentials(mAccessKey, mSecretKey);
    }

    private fun getAwsKinesisVideoClient(region: String): AWSKinesisVideoClient? {
        AppLog.e(TAG, "region $region")
        val awsKinesisVideoClient = AWSKinesisVideoClient(getBasicCredentials())
        awsKinesisVideoClient.setRegion(Region.getRegion(region))
        awsKinesisVideoClient.signerRegionOverride = region
        awsKinesisVideoClient.setServiceNameIntern("kinesisvideo")
        return awsKinesisVideoClient
    }

    /*******************************
     * Init Connection
     */
    private fun initConnection() {
        for (endpoint in mEndpointList) {
            if (endpoint.protocol == "WSS") {
                mWssEndpoint = endpoint.resourceEndpoint
            }
        }

        if (mClientId.isNullOrEmpty()) {
            mClientId = UUID.randomUUID().toString()
        }

        val userNames = ArrayList<String>(mIceServerList.size)
        val passwords = ArrayList<String>(mIceServerList.size)
        val ttls = ArrayList<Int>(mIceServerList.size)
        val urisList = ArrayList<List<String>>()
        if (mIceServerList.size > 0) {
            for (iceServer in mIceServerList) {
                userNames.add(iceServer.username)
                passwords.add(iceServer.password)
                ttls.add(iceServer.ttl)
                urisList.add(iceServer.uris)
            }
        }

        val stun = PeerConnection.IceServer
            .builder(
                String.format(
                    "stun:stun.kinesisvideo.%s.amazonaws.com:443",
                    awsRegionName
                )
            )
            .createIceServer()

        peerIceServers.add(stun)

        for (i in urisList.indices) {
            val turnServer = urisList[i].toString()
            val iceServer = PeerConnection.IceServer.builder(
                turnServer.replace("[", "").replace("]", "")
            )
                .setUsername(userNames[i])
                .setPassword(passwords[i])
                .createIceServer()
            AppLog.e(TAG, "IceServer details (TURN) = $iceServer")
            peerIceServers.add(iceServer)
        }

        localVideoTrack = peerConnectionFactory.createVideoTrack(VideoTrackID, videoSource)
//        localVideoTrack?.addSink(local_view)
        if (isAudioSent) {
            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack(AudioTrackID, audioSource)
            localAudioTrack?.setEnabled(true)
        }

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalAudioMode = audioManager?.mode ?: 0
        originalSpeakerphoneOn = audioManager?.isSpeakerphoneOn ?: false


    }

    /*******************************
     * Init WS  Connection
     */
    private fun initWsConnection() {
        val viewerEndpoint = "$mWssEndpoint?X-Amz-ChannelARN=$mChannelArn&X-Amz-ClientId=$mClientId"
        mCreds = getBasicCredentials()
        val signedUri: URI = getSignedUri(viewerEndpoint)
        val wsHost = signedUri.toString()
        val signalingListener: SignalingListener = object : SignalingListener() {
            override fun onSdpOffer(offerEvent: Event) {
                AppLog.e(TAG, "Received SDP Offer: Setting Remote Description ")
                val sdp = Event.parseOfferEvent(offerEvent)
                localPeer!!.setRemoteDescription(
                    KinesisVideoSdpObserver(),
                    SessionDescription(SessionDescription.Type.OFFER, sdp)
                )
                recipientClientId = offerEvent.senderClientId
                AppLog.e(
                    TAG,
                    "Received SDP offer for client ID: $recipientClientId.Creating answer"
                )
                createSdpAnswer()
            }

            override fun onSdpAnswer(answerEvent: Event) {
                AppLog.e(TAG, "SDP answer received from signaling")
                val sdp = Event.parseSdpEvent(answerEvent)
                val sdpAnswer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                localPeer!!.setRemoteDescription(KinesisVideoSdpObserver(), sdpAnswer)
                AppLog.e(TAG, "Answer Client ID: " + answerEvent.senderClientId)
                peerConnectionFoundMap[answerEvent.senderClientId] = localPeer!!
                // Check if ICE candidates are available in the queue and add the candidate
                handlePendingIceCandidates(answerEvent.senderClientId)
            }

            override fun onIceCandidate(message: Event) {
                AppLog.e(TAG, "Received IceCandidate from remote ")
                val iceCandidate = Event.parseIceCandidate(message)
                iceCandidate?.let { checkAndAddIceCandidate(message, it) }
                    ?: AppLog.e(TAG, "Invalid Ice candidate")
            }

            override fun onError(errorMessage: Event) {
                AppLog.e(TAG, "Received error message$errorMessage")
            }

            override fun onException(e: Exception) {
                AppLog.e(TAG, "Signaling client returned exception " + e.message)
                gotException = true
            }
        }

        try {
            client = SignalingServiceWebSocketClient(
                wsHost,
                signalingListener,
                Executors.newFixedThreadPool(10)
            )
            AppLog.e(
                TAG,
                "Client connection " + if (client?.isOpen != null) "Successful" else "Failed"
            )
        } catch (e: Exception) {
            gotException = true
        }
        if (isValidClient()) {
            AppLog.e(TAG, "Client connected to Signaling service " + client?.isOpen)
            AppLog.e(
                TAG, "Signaling service is connected: " +
                        "Sending offer as viewer to remote peer"
            ) // Viewer
            createSdpOffer()
            //Set time out
            checkTimeOut()
        } else {
            AppLog.e(TAG, "Error in connecting to signaling service")
            gotException = true
        }
    }

    private fun isValidClient(): Boolean {
        return client?.isOpen ?: false
    }

    private fun getSignedUri(viewerEndpoint: String): URI {
        AppLog.e(TAG, "mWssEndpoint $mWssEndpoint")
        return AwsV4Signer.sign(
            URI.create(viewerEndpoint),
            mCreds!!.awsAccessKeyId,
            mCreds!!.awsSecretKey,
            if (mCreds is AWSSessionCredentials) (mCreds as AWSSessionCredentials).sessionToken else "",
            URI.create(mWssEndpoint),
            awsRegionName
        )
    }

    // when local is set to be the master
    private fun createSdpAnswer() {
        localPeer!!.createAnswer(object : KinesisVideoSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                AppLog.e(TAG, "Creating answer : success")
                super.onCreateSuccess(sessionDescription)
                localPeer!!.setLocalDescription(KinesisVideoSdpObserver(), sessionDescription)
                val answer =
                    Message.createAnswerMessage(sessionDescription, false, recipientClientId)
                client?.sendSdpAnswer(answer)
                peerConnectionFoundMap[recipientClientId!!] = localPeer!!
                handlePendingIceCandidates(recipientClientId!!)
            }
        }, MediaConstraints())
    }

    private fun handlePendingIceCandidates(clientId: String?) {
        // Add any pending ICE candidates from the queue for the client ID
        AppLog.e(TAG, "Pending ice candidates found? " + pendingIceCandidatesMap[clientId])
        val pendingIceCandidatesQueueByClientId = pendingIceCandidatesMap[clientId]
        while (pendingIceCandidatesQueueByClientId != null && !pendingIceCandidatesQueueByClientId.isEmpty()) {
            val iceCandidate = pendingIceCandidatesQueueByClientId.peek()
            val peer = peerConnectionFoundMap[clientId]
            val addIce = peer!!.addIceCandidate(iceCandidate)
            AppLog.e(
                TAG,
                "Added ice candidate after SDP exchange " + iceCandidate + " " + if (addIce) "Successfully" else "Failed"
            )
            pendingIceCandidatesQueueByClientId.remove()
        }
        // After sending pending ICE candidates, the client ID's peer connection need not be tracked
        pendingIceCandidatesMap.remove(clientId)
    }

    private fun checkAndAddIceCandidate(message: Event, iceCandidate: IceCandidate) {
        // if answer/offer is not received, it means peer connection is not found. Hold the received ICE candidates in the map.
        if (!peerConnectionFoundMap.containsKey(message.senderClientId)) {
            AppLog.e(
                TAG,
                "SDP exchange is not complete. Ice candidate $iceCandidate + added to pending queue"
            )

            // If the entry for the client ID already exists (in case of subsequent ICE candidates), update the queue
            if (pendingIceCandidatesMap.containsKey(message.senderClientId)) {
                val pendingIceCandidatesQueueByClientId =
                    pendingIceCandidatesMap[message.senderClientId]
                pendingIceCandidatesQueueByClientId!!.add(iceCandidate)
                pendingIceCandidatesMap[message.senderClientId] =
                    pendingIceCandidatesQueueByClientId
            } else {
                val pendingIceCandidatesQueueByClientId: Queue<IceCandidate> = LinkedList()
                pendingIceCandidatesQueueByClientId.add(iceCandidate)
                pendingIceCandidatesMap[message.senderClientId] =
                    pendingIceCandidatesQueueByClientId
            }
        } else {
            AppLog.d(TAG, "Peer connection found already")
            // Remote sent us ICE candidates, add to local peer connection
            val peer = peerConnectionFoundMap[message.senderClientId]
            val addIce = peer!!.addIceCandidate(iceCandidate)
            AppLog.d(
                TAG,
                "Added ice candidate " + iceCandidate + " " + if (addIce) "Successfully" else "Failed"
            )
        }
    }

    // when mobile sdk is viewer
    private fun createSdpOffer() {
        val sdpMediaConstraints = MediaConstraints()
        sdpMediaConstraints.mandatory.add(
            MediaConstraints.KeyValuePair(
                "OfferToReceiveVideo",
                "true"
            )
        )
        sdpMediaConstraints.mandatory.add(
            MediaConstraints.KeyValuePair(
                "OfferToReceiveAudio",
                "true"
            )
        )
        if (localPeer == null) {
            createLocalPeerConnection()
        }
        localPeer!!.createOffer(object : KinesisVideoSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                super.onCreateSuccess(sessionDescription)
                localPeer!!.setLocalDescription(KinesisVideoSdpObserver(), sessionDescription)
                val sdpOfferMessage = Message.createOfferMessage(sessionDescription, mClientId)
                if (isValidClient()) {
                    client?.sendSdpOffer(sdpOfferMessage)
                }
            }
        }, sdpMediaConstraints)
    }

    private fun createLocalPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(peerIceServers)
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.continualGatheringPolicy =
            PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        localPeer = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : KinesisVideoPeerConnection() {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    super.onIceCandidate(iceCandidate)
                    val message = createIceCandidateMessage(iceCandidate)
                    AppLog.e(TAG, "Sending IceCandidate to remote peer $iceCandidate")
                    AppLog.e(TAG, "client ${client}")
                    client?.sendIceCandidate(message) /* Send to Peer */
                }

                override fun onAddStream(mediaStream: MediaStream) {
                    super.onAddStream(mediaStream)
                    AppLog.e(TAG, "Adding remote video stream (and audio) to the view")
                    addRemoteStreamToVideoView(mediaStream)
                }

                override fun onDataChannel(dataChannel: DataChannel) {
                    super.onDataChannel(dataChannel)
                }

                override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                    super.onIceConnectionChange(iceConnectionState)
                    AppLog.e(TAG, "onIceConnectionChange $iceConnectionState")
                }
            })

        if (localPeer != null) {
            localPeer?.getStats(RTCStatsCollectorCallback { rtcStatsReport ->
                val statsMap = rtcStatsReport.statsMap
                val entries: Set<Map.Entry<String, RTCStats>> = statsMap.entries
                for ((key, value) in entries) {
                    AppLog.e(TAG, "Stats: $key ,$value")
                }
            })
        }
        addDataChannelToLocalPeer()
        addStreamToLocalPeer()
    }

    private fun createIceCandidateMessage(iceCandidate: IceCandidate): jp.co.secom.design.cloudcam.aws.signaling.model.Message {
        val sdpMid = iceCandidate.sdpMid
        val sdpMLineIndex = iceCandidate.sdpMLineIndex
        val sdp = iceCandidate.sdp
        val messagePayload = ("{\"candidate\":\""
                + sdp
                + "\",\"sdpMid\":\""
                + sdpMid
                + "\",\"sdpMLineIndex\":"
                + sdpMLineIndex
                + "}")
        val senderClientId = mClientId!!
        return Message(
            "ICE_CANDIDATE", recipientClientId, senderClientId,
            String(
                Base64.encode(
                    messagePayload.toByteArray(),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )
            )
        )
    }

    private fun addDataChannelToLocalPeer() {
        AppLog.e(TAG, "Data channel addDataChannelToLocalPeer")
        val localDataChannel = localPeer!!.createDataChannel(
            "data-channel-of-$mClientId",
            DataChannel.Init()
        )
        localDataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {
                AppLog.e(
                    TAG,
                    "Local Data Channel onBufferedAmountChange called with amount $l"
                )
            }

            override fun onStateChange() {
                AppLog.e(
                    TAG,
                    "Local Data Channel onStateChange: state: " + localDataChannel.state()
                        .toString()
                )
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                // Send out data, no op on sender side
            }

        })
    }

    private fun addStreamToLocalPeer() {
        val stream =
            peerConnectionFactory.createLocalMediaStream(LOCAL_MEDIA_STREAM_LABEL)
        if (!stream.addTrack(localVideoTrack)) {
            AppLog.e(TAG, "Add video track failed")
        }
        localPeer!!.addTrack(stream.videoTracks[0], listOf(stream.id))
        if (isAudioSent) {
            if (!stream.addTrack(localAudioTrack)) {
                AppLog.e(TAG, "Add audio track failed")
            }
            if (stream.audioTracks.size > 0) {
                localPeer!!.addTrack(stream.audioTracks[0], listOf(stream.id))
                AppLog.e(TAG, "Sending audio track ")
            }
        }
    }

    private fun addRemoteStreamToVideoView(stream: MediaStream) {
        val remoteVideoTrack =
            if (stream.videoTracks != null && stream.videoTracks.size > 0) stream.videoTracks[0] else null
        val remoteAudioTrack =
            if (stream.audioTracks != null && stream.audioTracks.size > 0) stream.audioTracks[0] else null
        if (remoteAudioTrack != null) {
            remoteAudioTrack.setEnabled(true)
            AppLog.e(
                TAG,
                "remoteAudioTrack received: State=" + remoteAudioTrack.state().name
            )
            audioManager!!.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager!!.isSpeakerphoneOn = true
            remoteAudioTrack.setVolume(0.0)
        }
        if (remoteVideoTrack != null) {
            try {
                AppLog.e(
                    TAG,
                    "remoteVideoTrackId=" + remoteVideoTrack.id() + " videoTrackState=" + remoteVideoTrack.state()
                )
                //Remove handler
                handlerCheckConnect.removeCallbacks(runnableCheckConnect)
            } catch (e: Exception) {
                AppLog.e(TAG, "Error in setting remote video view$e")
            }
        } else {
            AppLog.e(TAG, "Error in setting remote track")
        }
    }

    /**************
     * Check time out after send offer
     */
    private var handlerCheckConnect: Handler = Handler(Looper.getMainLooper())
    private var runnableCheckConnect: Runnable = Runnable {
        AppLog.e(TAG, "Error time out connect")
    }

    private fun checkTimeOut() {
        handlerCheckConnect.postDelayed(runnableCheckConnect, TIME_OUT_CONNECT)
    }


}