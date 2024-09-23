package com.example.testewrtc;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import androidx.appcompat.app.AppCompatActivity;

import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1000;
    private static final int REQUEST_PERMISSIONS_CODE = 1001;
    private static final int DISPLAY_WIDTH = 1280;
    private static final int DISPLAY_HEIGHT = 720;
    private static final int DISPLAY_DPI = 300;

    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;

    private Intent mediaProjectionPermissionResultData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inicializa o MediaProjectionManager
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // Inicializa o PeerConnectionFactory
        initializePeerConnectionFactory();

        // Solicitar permissões para Android 13 e superior
        checkAndRequestPermissions();
    }

    private void initializePeerConnectionFactory() {
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory();
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            List<String> permissionsToRequest = new ArrayList<>();
            if (checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION);
            }
            // Não use CAPTURE_VIDEO_OUTPUT diretamente em versões anteriores
            if (!permissionsToRequest.isEmpty()) {
                requestPermissions(permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
            } else {
                startScreenCapture();
            }
        } else {
            // Para versões anteriores do Android, a permissão MEDIA_PROJECTION é suficiente
            startScreenCapture();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startScreenCapture();
            } else {
                Log.e("Permissions", "Permissões necessárias não foram concedidas.");
            }
        }
    }

    private void startScreenCapture() {
        Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, SCREEN_CAPTURE_REQUEST_CODE);

        // Iniciar o serviço em primeiro plano para lidar com a captura de tela
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent); // Para Android O e superior
        } else {
            startService(serviceIntent); // Para versões anteriores do Android
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // Armazena o Intent de permissão para capturar a tela
                mediaProjectionPermissionResultData = data;

                // Inicializa o MediaProjection
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
                if (mediaProjection != null) {
                    createPeerConnection();
                    startMediaProjection(mediaProjection);
                }
            } else {
                Log.e("MediaProjection", "Permissão de captura de tela negada");
            }
        }
    }

    private void createPeerConnection() {
        // Configura servidores STUN para WebRTC
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL;

        // Inicializa o PeerConnection
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                // Tratar IceCandidate (enviar para o servidor de sinalização)
                Log.d("WebRTC", "IceCandidate gerado: " + iceCandidate.toString());
                // Enviar o iceCandidate para o servidor via WebSocket ou outro mecanismo de sinalização
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

            @Override
            public void onAddStream(MediaStream mediaStream) {
                // Lidar com o stream de mídia recebido (se necessário)
            }

            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {}

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}

            @Override
            public void onRemoveStream(MediaStream mediaStream) {}

            @Override
            public void onDataChannel(org.webrtc.DataChannel dataChannel) {}

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {}
        });
    }

    private void startMediaProjection(MediaProjection mediaProjection) {
        EglBase eglBase = EglBase.create();

        // Inicializa o SurfaceTextureHelper para capturar os frames da tela
        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());

        // Cria o VideoCapturer para capturar a tela
        VideoCapturer videoCapturer = createScreenCapturer(mediaProjection);

        VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());

        // Converte SurfaceTexture para Surface
        Surface surface = new Surface(surfaceTextureHelper.getSurfaceTexture());

        // Cria e inicia o VirtualDisplay para capturar a tela
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                DISPLAY_WIDTH,
                DISPLAY_HEIGHT,
                DISPLAY_DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
        );

        // Configura o WebRTC para enviar o stream capturado
        VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("screenTrack", videoSource);
        MediaStream mediaStream = peerConnectionFactory.createLocalMediaStream("screenStream");
        mediaStream.addTrack(videoTrack);
        peerConnection.addStream(mediaStream);
    }

    private VideoCapturer createScreenCapturer(MediaProjection mediaProjection) {
        return new ScreenCapturerAndroid(mediaProjectionPermissionResultData, new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.e("ScreenCapturer", "Screen capturer stopped");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }

        // Parar o serviço em primeiro plano
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        stopService(serviceIntent);
    }
}
