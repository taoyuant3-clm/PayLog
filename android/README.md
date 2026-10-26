# 嗶記帳（PayLog）— Android App

手機 NFC 感應付款後，自動跳出提醒請你填寫這筆消費。

| 欄位 | 誰填 |
|---|---|
| 消費總值 | 你（若通知內有金額會預先帶入） |
| 支付卡：VISA / MASTER / 悠遊卡 | 你（若通知內有卡別會預先選好，並記住上次的選擇） |
| 用途（分類 + 備註） | 你 |
| 日期、時間 | 自動（付款當下） |
| 地點 | 自動（GPS 定位反查地址） |
| 商家 | 自動（從付款通知解析） |

## 運作原理

Android 不允許一般 App 攔截 NFC 付款本身。合規做法是：Google 錢包 / Samsung Wallet / 銀行 App 在付款完成時會發出「已付款 NT$xxx」通知，本 App 透過**通知存取權**讀到後：

1. 建立一筆「待填寫」紀錄（就算你滑掉提醒也不會遺失）
2. 跳出橫幅通知「剛剛付款 NT$85，記一下？」→ 點開即是填寫畫面
3. 同一筆付款若錢包與銀行各發一則通知，10 分鐘內同金額只提醒一次，第二則的卡別 / 商家會自動補進去
4. 實體悠遊卡等沒有通知的情況，可用主畫面「＋ 記一筆」或下拉選單的「記一筆」快速設定磚

> Android 10 起，系統禁止背景 App 直接開啟畫面，所以是以「高優先通知」彈出，點一下進入填寫頁 — 這是 Google Play 允許的唯一方式。

## v1.2 新增

**發票 / 收據照片**
- 填寫畫面新增「拍照」「從相簿選」，每筆可附多張；點縮圖可放大或刪除
- 照片壓縮後存在 App 私有目錄（長邊 1800px，約 200–500 KB / 張），其他 App 讀不到；刪除紀錄時一併刪除
- 主畫面清單以 📎 數字顯示附圖張數
- 匯出可選「表格 + 發票照片（ZIP）」：照片依「日期_金額」命名，CSV 新增「照片」欄對應檔名
- 不需要相機權限（交給系統相機 App 拍攝）

**用途自訂**
- 用途選單最後一項「＋ 新增用途…」，填寫時就能直接新增
- 設定 →「管理用途」：新增、刪除、恢復預設；已記錄的消費不受影響

## v1.1 新增

**付款後自動彈出填寫畫面**（不必再點通知）
- 需要權限「顯示在其他應用程式上層」（Android 10 起背景 App 要開畫面的唯一例外）
- 螢幕鎖定時用「全螢幕通知」亮屏顯示填寫畫面
- 小米 / 紅米 / POCO 另需在 應用程式資訊 → 其他權限 開啟「在背景中顯示彈出式視窗」

**網購雙重認證（OTP）簡訊觸發**
- 收到銀行 3D 驗證 / 動態密碼簡訊即建立一筆「待填寫」（預設分類：購物）
- 延遲 60 秒才彈出（可設 30/60/120 秒或立即），避免擋住你輸入驗證碼
- 預設只算含金額或「交易／消費／網路」字樣的驗證碼；登入、註冊、綁定類不算（設定可改為全部都算）
- 驗證簡訊與之後銀行的消費通知若金額相同，只算一筆
- 只讀取剛收到的那則簡訊，不讀收件匣、不儲存簡訊內容或驗證碼
- 未授予簡訊權限時，改由簡訊 App 的通知辨識（備援）

> Google Play 上架注意：Play 只允許預設簡訊 App 等少數類別使用 RECEIVE_SMS。上架版請移除此權限，改靠通知備援；自用側載版不受影響。

## 專案結構

```
app/src/main/java/tw/clmchiu/paylog/
  PaymentListenerService.java  偵測付款通知
  SmsReceiver.java             網購驗證簡訊
  Detector.java / Popup.java / PopupReceiver.java  建立紀錄、自動彈出
  Photos.java / PhotoProvider.java  發票照片存放、壓縮、相機寫入
  Export.java                  CSV / ZIP 匯出
  PaymentParser.java           解析金額 / 卡別 / 商家（含單元測試）
  RecordActivity.java          填寫畫面（自動日期時間、定位）
  MainActivity.java            本月合計、清單、匯出 CSV、設定
  Notifier.java / SkipReceiver.java / RecordTileService.java
  Db.java / Record.java / Prefs.java / Fmt.java / Ui.java
store/                         上架素材與文案
```

純 Android 框架 API、零第三方套件；minSdk 26（Android 8.0）、targetSdk 36。資料只存在手機本機 SQLite。

## 建置（Android Studio）

1. 安裝 Android Studio（Narwhal 或更新版），開啟此資料夾，等待 Gradle 同步完成
2. 接上手機（開啟 USB 偵錯）→ 按 ▶ Run 先實機測試
3. App 內：開啟付款偵測 → 設定 → 「測試：模擬一次付款提醒」確認會彈出

> 若用 APK 側載（非 Play 安裝）在 Android 13+ 開啟通知存取權時被擋：到 設定 → 應用程式 → 嗶記帳 → 右上角 ⋮ →「允許受限制的設定」。從 Play 商店安裝不會有此問題。

## 產生上架用 AAB

1. 產生簽章金鑰（只做一次，**務必備份，遺失就無法更新 App**）：
   ```
   keytool -genkeypair -v -keystore paylog-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias paylog
   ```
2. 在專案根目錄建立 `keystore.properties`：
   ```
   storeFile=paylog-release.jks
   storePassword=你的密碼
   keyAlias=paylog
   keyPassword=你的密碼
   ```
3. 執行 `./gradlew bundleRelease`（Windows：`gradlew.bat bundleRelease`）
   輸出：`app/build/outputs/bundle/release/app-release.aab`

（或 Android Studio：Build → Generate Signed App Bundle）

## 上架 Google Play

1. 註冊 Play Console 開發者帳號（一次性 US$25）
2. 建立應用程式 → 上傳 `app-release.aab`
3. 填寫商店資訊：文案見 `store/listing_zh-TW.md`，圖示 `store/icon_512.png`，主題圖 `store/feature_graphic_1024x500.png`，另需至少 2 張手機截圖（實機截圖即可）
4. 隱私權政策：把 `store/privacy_policy.md` 發佈成公開網頁（例如 Google Sites / GitHub Pages），網址填入 Play Console
5. 資料安全性表單：答案見 `store/data_safety.md`
6. 個人開發者帳號需先跑 **封閉測試（12 位測試者 × 14 天）** 才能申請正式上架
7. 上架前把 `app/build.gradle.kts` 的 `applicationId` 確認為你要的唯一 ID（上架後不能改）

## 偵測範圍

- 一律偵測：Google 錢包、Samsung Wallet
- 預設也偵測（可在設定關閉）：其他 App 的通知同時含「金額 + 付款關鍵字」且不像行銷推播 — 涵蓋銀行信用卡即時消費通知、悠遊付等
- 一律不偵測：LINE、Messenger、WhatsApp、簡訊、Gmail 等聊天類
- 誤判時按通知上的「不再偵測此App」即可封鎖該 App
