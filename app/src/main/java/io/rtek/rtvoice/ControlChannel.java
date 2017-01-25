package io.rtek.rtvoice;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.AsyncTask;
import android.support.v4.util.Pair;
import android.util.Log;

import org.apache.http.conn.ssl.SSLSocketFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Created by rctl on 2017-01-22.
 */

public class ControlChannel extends Thread implements ICallListener {

    //Control
    IControlChannelListener listener;
    int number = 0;
    PrintWriter out;
    BufferedReader in;
    String server;
    Call currentCall;

    public ControlChannel(IControlChannelListener listener, String server){
        this.listener = listener;
        this.server = server;
    }

    //Actions
    public void answer(int callerId){
        transmitt("ANSWER FROM " + Integer.toString(callerId));
    }

    public void decline(int callerId){
        transmitt("DECLINE FROM " + Integer.toString(callerId));
        currentCall = null;
    }

    public boolean dial(int callerId){
        if(activeCall())
            return false;
        transmitt("DIAL " + Integer.toString(callerId));
        listener.callStatusChanged(CallStatus.DIALING);
        currentCall = new Call(this, this.number, callerId);
        return true;
    }

    public boolean hangup(){
        transmitt("HANGUP FROM " + Integer.toString(currentCall.getRemoteCallerId()));
        currentCall.close();
        return true;
    }

    @Override
    public void callStatusChanged(CallStatus status) {
        listener.callStatusChanged(status);
    }

    @Override
    public void callEnded() {
        listener.callEnded(0);
        this.currentCall = null;
    }

    //Statuses
    public boolean activeCall(){
        if (currentCall == null)
            return false;
        return !currentCall.closed();
    }

    public int getNumber(){
        return this.number;
    }

    private int createCall = 0;

    public void createIncoming(int number){
        if (!activeCall()) {
            listener.incoming(number);
            currentCall = new Call(this, this.number, number);
        }
    }

    //Internals
    private void transmitt(final String message){
        new Thread(new Runnable() {
            @Override
            public void run() {
                while(socket != null && !socket.isConnected()){}
                out.println(message);
            }
        }).start();
    }
    Socket socket;

    private boolean closing = false;
    public void close(){
        closing = true;
    }
    public boolean isCancelled(){
        return closing;
    }
    //Main
    public void run() {
        while(!isCancelled()){
            try {
                listener.ControlChannelDisconnected();
                Pair<TrustManagerFactory, KeyManagerFactory> km = listener.getTrustManagerFactory();
                if(km == null || km.first == null || km.second == null){
                    throw new Exception("No valid truststore provided");
                }
                SSLContext context = SSLContext.getInstance("SSL");
                KeyManager[] keymanagers =  km.second.getKeyManagers();
                context.init(keymanagers, km.first.getTrustManagers(), new SecureRandom());
                socket = context.getSocketFactory().createSocket(this.server, 1338);
                Log.w("CC", "Connecting");
                while(!socket.isConnected()){}
                listener.ControlChannelConnected();
                Log.w("CC", "Connected");
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String savedNumber = listener.getString("number");
                if(!savedNumber.isEmpty()){
                    //Try to register
                    out.println("REGISTER " + savedNumber.split(":")[0] + " " + savedNumber.split(":")[1]);
                    String response = in.readLine();
                    if(response.equals("UNKNOWN NUMBER")){
                        //Number is unknown, generate new
                    }else if (response.equals("INVALID KEY")){
                        //Key was invalid, generate new
                    }else if (response.equals("ASSOCIATED")){
                        //All was ok, we can now set number locally
                        this.number = Integer.parseInt(savedNumber.split(":")[0]);
                        listener.setNumber(this.number);
                        if (!listener.getString("gcm").equals("registered")){
                            if(listener.getGcmDeviceId() != null)
                                out.println("DEVICE " + listener.getGcmDeviceId());
                        }
                    }
                }

                //If no number has been saved
                if(number == 0){
                    out.println("REQUEST NUMBER");
                    String numberKey = in.readLine();
                    this.number = Integer.parseInt(numberKey.split(" ")[1]);
                    listener.setNumber(this.number);
                    Log.w("CC", Integer.toString(number));
                    listener.saveString("number", numberKey.split(" ")[1] + ":" + numberKey.split(" ")[2]);
                    out.println("DEVICE " + listener.getGcmDeviceId());
                }

                while(socket.isConnected() && !isCancelled()){
                    final String cmd = in.readLine();
                    listener.messageReceived(cmd);
                    Log.w("CC", cmd);
                    if(cmd.equals("DEVICE ADDED")){
                        listener.saveString("gcm", "registered");
                    }
                    if(cmd.startsWith("CALL FROM ")){
                        int callerId = Integer.parseInt(cmd.split(" ")[2]);
                        if (activeCall()){
                            out.println("BUSY FROM " + Integer.toString(callerId));
                        }else{
                            listener.incoming(callerId);
                            currentCall = new Call(this, this.number, callerId);
                        }
                    }
                    if(cmd.startsWith("LINK ")) {
                        int callerId = Integer.parseInt(cmd.split(" ")[1]);
                        listener.initializeCall(callerId);
                        String key = "";
                        if(cmd.split(" ").length == 4){
                            key = cmd.split(" ")[3];
                            listener.callSecure(true);
                        }else{
                            listener.callSecure(false);
                        }
                        currentCall.start(server + ":" + cmd.split(" ")[2], key);
                    }
                    if(cmd.startsWith("HANGUP ")){
                        int callerId = Integer.parseInt(cmd.split(" ")[1]);
                        currentCall.close();
                        listener.callEnded(callerId);
                    }
                    if(cmd.startsWith("BUSY ")){
                        int callerId = Integer.parseInt(cmd.split(" ")[1]);
                        listener.callStatusChanged(CallStatus.BUSY);
                        listener.callEnded(callerId);
                        currentCall = null;
                    }
                    if(cmd.startsWith("DECLINE ")){
                        int callerId = Integer.parseInt(cmd.split(" ")[1]);
                        listener.callStatusChanged(CallStatus.DECLINED);
                        listener.callEnded(callerId);
                        currentCall = null;
                    }
                }
            }catch (Exception e){
                e.printStackTrace();
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        }
        return;
    }
}

