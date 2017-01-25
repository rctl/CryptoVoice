package io.rtek.rtvoice;

/**
 * Created by rctl on 2017-01-23.
 */

public interface ICallListener {
    boolean hangup();
    void callStatusChanged(CallStatus status);
    void callEnded();
}
