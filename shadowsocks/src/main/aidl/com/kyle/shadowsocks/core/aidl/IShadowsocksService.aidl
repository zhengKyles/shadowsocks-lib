package com.kyle.shadowsocks.core.aidl;

import com.kyle.shadowsocks.core.aidl.IShadowsocksServiceCallback;

interface IShadowsocksService {
  int getState();
  String getProfileName();

  void registerCallback(in IShadowsocksServiceCallback cb);
  void startListeningForBandwidth(in IShadowsocksServiceCallback cb, long timeout);
  oneway void stopListeningForBandwidth(in IShadowsocksServiceCallback cb);
  oneway void unregisterCallback(in IShadowsocksServiceCallback cb);
}
