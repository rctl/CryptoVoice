package io.rtek.rtvoice;

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
    String getString(String key);
    void saveString(String key, String value);
    void ControlChannelConnected();
    void ControlChannelDisconnected();
    String getGcmDeviceId();
}
