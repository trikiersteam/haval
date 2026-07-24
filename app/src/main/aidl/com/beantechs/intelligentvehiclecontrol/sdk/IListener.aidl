// IListener.aidl
package com.beantechs.intelligentvehiclecontrol.sdk;

interface IListener {
    void onDataChanged(in String key, in String value);
}
