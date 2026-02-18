package com.RobinNotBad.BiliClient.activity.player;

import android.graphics.Color;
import android.util.Log;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.netease.hearttouch.brotlij.Brotli;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Timer;
import java.util.TimerTask;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class PlayerDanmuClientListener extends WebSocketListener {
    public long mid = 0;
    public long roomid = 0;
    public String key = "";
    private int seq = 1;
    private final MessageData messageData = new MessageData();

    private Timer heartTimer = null;

    // P0：WebSocket 回调在后台线程，release() 会置空；用 volatile 让其它线程及时看到，避免 NPE。
    public volatile PlayerActivity playerActivity;

    /**
     * P0: 主动释放资源，避免 WebSocket/Timer 持有 Activity 引用导致泄漏。
     * <p>
     * PlayerActivity.onDestroy() 会调用该方法。
     */
    public void release() {
        try {
            if (heartTimer != null) {
                heartTimer.cancel();
                heartTimer = null;
            }
        } catch (Exception ignore) {
            heartTimer = null;
        }
        playerActivity = null;
    }


    @Override
    public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
        super.onOpen(webSocket, response);
        Log.e("debug", "WebSocket已连接");

        try {
            if (heartTimer != null) {
                heartTimer.cancel();
                heartTimer = null;
            }
        } catch (Exception ignore) {
            heartTimer = null;
        }

        //发送认证包
        try {
            JSONObject object = new JSONObject();
            if (SharedPreferencesUtil.getBoolean("live_by_guest", false)) object.put("uid", 0);
            else object.put("uid", mid);
            object.put("roomid", roomid);
            object.put("protover", 3);
            object.put("platform", "web");
            // 兼容 API19：避免使用 Map.getOrDefault（Android 低版本可能不存在该默认方法）
            String buvid = "";
            try {
                if (NetWorkUtil.getCookies() != null) {
                    String v = NetWorkUtil.getCookies().get("buvid3");
                    if (v != null) {
                        buvid = v;
                    }
                }
            } catch (Exception ignore) {
            }
            object.put("buvid", buvid);
            object.put("type", 2);
            object.put("key", key);

            webSocket.send(messageData.getData(3, 7, object.toString().getBytes(Charset.forName("UTF-8"))));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class MessageData {

        private byte[] getPacket(int protocolVersion, int actionCode, byte... data) {
            int headerSize = 16;
            int totalSize = headerSize + data.length;
            byte[] packet = new byte[totalSize];

            packet[0] = (byte) (totalSize >> 24);
            packet[1] = (byte) (totalSize >> 16);
            packet[2] = (byte) (totalSize >> 8);
            packet[3] = (byte) (totalSize);

            packet[4] = (byte) (0);
            packet[5] = (byte) (headerSize);

            packet[6] = (byte) 0;
            packet[7] = (byte) protocolVersion;

            packet[8] = (byte) 0;
            packet[9] = (byte) 0;
            packet[10] = (byte) 0;
            packet[11] = (byte) actionCode;

            packet[12] = (byte) (seq >> 24);
            packet[13] = (byte) (seq >> 16);
            packet[14] = (byte) (seq >> 8);
            packet[15] = (byte) (seq);
            seq++;

            System.arraycopy(data, 0, packet, headerSize, data.length);
            Log.d("BiliClient", "getPackage totalLen=" + totalSize + ", data=" + new String(data) + ", result=" + ByteString.of(packet).hex());
            return packet;
        }

        public ByteString getData(int protocolVersion, int actionCode, byte... data) {
            return ByteString.of(getPacket(protocolVersion, actionCode, data));
        }

        public ByteString getBrotliData(int protocolVersion, int actionCode, byte... data) {
            byte[] encodedData = Brotli.compress(getPacket(protocolVersion, actionCode, data));
            return ByteString.of(getPacket(3, actionCode, encodedData));
        }

    }

    @Override
    public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
        super.onMessage(webSocket, bytes);

        int actionCode = bytes.getByte(11);
        switch (actionCode) {
            case 8:
                Log.e("debug", "弹幕流认证成功");
                try {
                    if (heartTimer != null) {
                        heartTimer.cancel();
                        heartTimer = null;
                    }
                } catch (Exception ignore) {
                    heartTimer = null;
                }
                heartTimer = new Timer();
                TimerTask heartTimerTask = new TimerTask() {
                    @Override
                    public void run() {
                        Log.e("debug", "发送心跳包");
                        try {
                            webSocket.send(messageData.getData(1, 2, "".getBytes(Charset.forName("UTF-8"))));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                };
                heartTimer.schedule(heartTimerTask, 3000, 32000);
                break;

            case 5:
                plainPackage(bytes);
                break;

            default:
                break;
        }
    }

    @Override
    public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
        super.onClosed(webSocket, code, reason);
        Log.e("debug", "WebSocket连接关闭：" + reason + "(" + code + ")");

        try {
            if (heartTimer != null) {
                heartTimer.cancel();
                heartTimer = null;
            }
        } catch (Exception ignore) {
            heartTimer = null;
        }
    }

    @Override
    public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
        super.onFailure(webSocket, t, response);

        Writer writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        t.printStackTrace(printWriter);

        Log.e("debug", "WebSocket连接失败：" + writer);

        try {
            if (heartTimer != null) {
                heartTimer.cancel();
                heartTimer = null;
            }
        } catch (Exception ignore) {
            heartTimer = null;
        }
    }

    //处理普通包
    private void plainPackage(ByteString bytes) {
        final PlayerActivity pa = playerActivity;
        if (pa == null) {
            return;
        }
        try {
            JSONObject result;

            //有些包不会压缩，要判断一下，虽然方式有点（
            ByteString bytes2 = bytes.substring(bytes.getByte(5));
            if (Brotli.decompress(bytes2.toByteArray()).length > 5) {
                ByteString bytes3 = ByteString.of(Brotli.decompress(bytes2.toByteArray()));
                result = new JSONObject(bytes3.substring(bytes3.getByte(5)).utf8()); //问就是懒得用别的方式sub
            } else if (bytes2.utf8().contains("{"))
                result = new JSONObject(bytes2.utf8().substring(bytes2.utf8().indexOf("{")));
            else return;

            JSONObject data;
            switch (result.getString("cmd")) {

                //聊天弹幕
                case "DANMU_MSG":
                    JSONArray info = result.getJSONArray("info");
                    String nickname = info.getJSONArray(0).getJSONObject(15).getJSONObject("user").getJSONObject("base").getString("name");
                    String content = info.getString(1);
                    if (SharedPreferencesUtil.getBoolean("player_danmaku_showsender", true))
	                    pa.addDanmaku(nickname + "：" + content, Color.WHITE);
	                else pa.addDanmaku(content, Color.WHITE);

                    Log.e("debug", "pkg_dm");
                    break;

                //看过的人数
                case "WATCHED_CHANGE":
                    data = result.getJSONObject("data");
	                pa.online_number = data.getString("text_large");
                    break;

                case "INTERACT_WORD":
                    data = result.getJSONObject("data");

                    //进入直播间
                    if (data.getInt("msg_type") == 1)
	                    pa.addDanmaku(data.getString("uname") + " 进入了直播间", Color.CYAN, 12, 4, 0);

                    break;

                //送礼弹幕
                case "SEND_GIFT":
                    data = result.getJSONObject("data");
                    String content2 = data.getString("uname") + " " + data.getString("action") + data.getInt("num") + "个" + data.getString("giftName");
	                pa.addDanmaku(content2, Color.WHITE, 25, 1, Color.argb(160, 255, 80, 80));
                    break;

                //特殊入场
                case "ENTRY_EFFECT":
                    data = result.getJSONObject("data");
	                pa.addDanmaku(data.getString("copy_writing").replace("<%", "").replace("%>", ""), Color.WHITE, 25, 1, Color.argb(160, 80, 80, 255));
                    break;

                //通知消息
                case "NOTICE_MSG":
	                pa.addDanmaku(result.getString("msg_common"), Color.RED, 25, 1, Color.argb(60, 255, 255, 255));
                    break;

                //直播间消息修改
                case "ROOM_CHANGE":
                    data = result.getJSONObject("data");
	                pa.runOnUiThread(() -> {
                        try {
	                        if (pa.text_title != null) {
	                            pa.text_title.setText(data.getString("title"));
	                        }
                        } catch (Exception ignore) {
                        }
                    });
                    break;

                default:
                    break;
            }

        } catch (Exception e) {
            Writer writer = new StringWriter();
            PrintWriter printWriter = new PrintWriter(writer);
            e.printStackTrace(printWriter);

            Log.e("debug", "解析普通包时错误：" + writer);
        }
    }
}
