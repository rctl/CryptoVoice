package io.rtek.rtvoice;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * Created by rctl on 2017-01-23.
 */

public class Call extends Thread {

    //Voice and transmission
    private Thread downlink;
    private DatagramSocket sock;
    private byte[] transmittBuffer;
    private byte[] receiveBuffer;
    private AudioRecord recorder;
    private AudioTrack track;
    private String server;
    //Constants
    final private int sampleRate = 16000;
    final private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    final private int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    final private int minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
    private int remoteCallerId;
    private int localCallerId;
    //Listener
    private ICallListener listener;
    //Interrupt
    private boolean closing = false;

    public Call(ICallListener listener, int localCallerId, int remoteCallerId){
        this.listener = listener;
        this.localCallerId = localCallerId;
        this.remoteCallerId = remoteCallerId;
    }

    public void close(){
        this.closing = true;
        if (sock != null){
            sock.close();
        }
        if (recorder != null){
            try{
                recorder.stop();
            }catch (Exception e){

            }
            try{
                recorder.release();
            }catch (Exception e){

            }
        }
        listener.callEnded();
    }

    public void start(String server){
        this.server = server;
        start();
    }

    public boolean closed(){
        return this.closing;
    }

    public int getRemoteCallerId(){
        return remoteCallerId;
    }

    public int getLocalCallerId(){
        return localCallerId;
    }

    public void run() {
        try {
            track = new AudioTrack(AudioManager.STREAM_VOICE_CALL, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize, AudioTrack.MODE_STREAM);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBufSize * 10);
            transmittBuffer = new byte[minBufSize];
            receiveBuffer = new byte[minBufSize];
            listener.callStatusChanged(CallStatus.CONNECTING);
            NoiseSuppressor.create(MediaRecorder.AudioSource.MIC);
            AcousticEchoCanceler.create(MediaRecorder.AudioSource.MIC);
            AutomaticGainControl.create(MediaRecorder.AudioSource.MIC);
            recorder.startRecording();
            track.play();

            sock = new DatagramSocket();
            sock.connect(new InetSocketAddress(server.split(":")[0], Integer.parseInt(server.split(":")[1])));
            listener.callStatusChanged(CallStatus.CONNECTED);
            //Bind to response port
            downlink = new Thread(new Runnable() {
                @Override
                public void run() {
                    int errors = 10;
                    while (true) {
                        if(closing)
                            return;
                        try {
                            while (sock.isConnected()) {
                                if(closing)
                                    return;
                                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                                sock.receive(receivePacket);
                                Log.w("VOICE", "Packet of " + receivePacket.getLength() + " was received from " + receivePacket.getAddress());
                                track.write(receivePacket.getData(), 0, receivePacket.getLength());
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            errors--;
                            if (errors <= 0) {
                                listener.callStatusChanged(CallStatus.ERROR);
                                close();
                                listener.callEnded();
                                return;
                            }
                        }
                    }
                }
            });
            downlink.start();

            int errors = 10;
            while (true) {
                if(closing)
                    return;
                try {
                    while (sock.isConnected()) {
                        if(closing)
                            return;
                        int read = recorder.read(transmittBuffer, 0, transmittBuffer.length);
                        DatagramPacket packet = new DatagramPacket(transmittBuffer, read);
                        sock.send(packet);
                    }
                    if (!closing) //reconnect from uplink thread if connection stopped but not interrupted
                        sock.connect(new InetSocketAddress(server.split(":")[0], Integer.parseInt(server.split(":")[1])));
                } catch (Exception e) {
                    e.printStackTrace();
                    errors--;
                    if (errors <= 0) {
                        listener.callStatusChanged(CallStatus.ERROR);
                        close();
                        listener.callEnded();
                        return;
                    }
                }
            }
            //Finish uplink
        } catch (Exception e) {
            listener.callStatusChanged(CallStatus.ERROR);
            close();
            listener.callEnded();
        }

    }
}
