package io.rtek.rtvoice;

import android.support.v4.util.Pair;

import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Created by rctl on 2017-01-22.
 */

public interface IControlChannelListener {
    void setNumber(int number);
    void incoming(int number);
    void initializeCall(int number);
    void callEnded(int number);
    void callStatusChanged(CallStatus status);
    void messageReceived(String message); //For debugging
    void callSecure(boolean state);
    String getString(String key);
    void saveString(String key, String value);
    void ControlChannelConnected();
    void ControlChannelDisconnected();
    Pair<TrustManagerFactory, KeyManagerFactory> getTrustManagerFactory();
    String getGcmDeviceId();
}
