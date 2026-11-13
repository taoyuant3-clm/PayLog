package tw.clmchiu.paylog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

/**
 * 接收簡訊，辨識網購雙重認證（OTP）簡訊並觸發記帳。
 * 只讀取剛收到的這一則簡訊；不讀取收件匣，也不儲存簡訊內容或驗證碼。
 */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        if (!Prefs.smsEnabled(context)) return;
        try {
            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (parts == null || parts.length == 0) return;
            StringBuilder body = new StringBuilder();
            String sender = null;
            for (SmsMessage m : parts) {
                if (m == null) continue;
                if (sender == null) sender = m.getDisplayOriginatingAddress();
                body.append(m.getDisplayMessageBody());
            }
            handle(context, body.toString(), sender);
        } catch (Exception e) {
            Log.w("PayLog", "sms parse failed", e);
        }
    }

    static void handle(Context c, String text, String sender) {
        String s = sender == null ? "" : sender.trim();
        long now = System.currentTimeMillis();
        if (PaymentParser.isPurchaseOtp(text, Prefs.otpAll(c))) {
            Detector.onOtp(c, Card.analyze(c, text, null, now), s);
        } else if (PaymentParser.isCardSpendSms(text)) {
            // 刷卡消費通知簡訊（例如在日本刷實體卡、香港 / 大陸發卡行的簽賬短訊）
            Detector.onCardSpendSms(c, Card.analyze(c, text, null, now), s);
        }
    }
}
