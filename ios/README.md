# 嗶記帳（PayLog）— iPhone 版

用 Apple Pay 感應付款後，自動打開 App 請你填寫這筆消費。

| 欄位 | 誰填 |
|---|---|
| 消費總值 | 你（Apple Pay 交易金額會先帶入） |
| 支付卡：VISA / MASTER / 悠遊卡 | 你（可由卡片名稱自動判斷，並記住上次選擇） |
| 用途（分類 + 備註） | 你 |
| 日期、時間 | 自動 |
| 地點 | 自動（定位反查地址） |
| 商家 | 自動（Apple Pay 交易帶入） |

## 與 Android 版的差異

iOS 不允許 App 讀取其他 App 的通知。iPhone 版改用 iOS 17 起內建的 **捷徑 →自動化 →「交易」觸發**：

1. Apple Pay 感應付款的當下，自動化執行本 App 的「記錄付款」動作
2. 動作會帶入金額、商家、卡片名稱，建立一筆「待填寫」紀錄
3. 自動打開 App 的填寫畫面（就是你要的「彈出 App 要求填寫」）
4. 若手機鎖定中無法打開 App，會改跳一則通知，點開即可填寫
5. 滑掉也不會漏記，會以「待填寫」留在清單

自動化建立步驟寫在 App 的「設定」頁，只需設定一次。實體悠遊卡等非 Apple Pay 付款，用主畫面「記一筆」手動記錄。

## 專案內容

```
project.yml                  XcodeGen 設定（產生 Xcode 專案）
PayLog/
  PayLogApp.swift            App 進入點、通知點擊處理
  LogPaymentIntent.swift     「記錄付款」捷徑動作（App Intent）
  RecordView.swift           填寫畫面（自動日期時間、定位）
  ContentView.swift          本月合計、清單、匯出 CSV
  SettingsView.swift         自動化設定步驟、測試按鈕
  Models.swift / Parser.swift / LocationService.swift / CSV.swift
  Assets.xcassets            App 圖示
.github/workflows/ios.yml    沒有 Mac 時，用 GitHub 雲端 Mac 檢查能否編譯
```

SwiftUI + SwiftData，零第三方套件，iOS 17 以上。資料只存在 iPhone 本機。

## 安裝到 iPhone（需要一台 Mac）

iPhone App 只能用 Mac 上的 Xcode 建置與簽章，這是 Apple 的限制。

1. Mac 安裝 Xcode（App Store 免費）與 XcodeGen：
   ```
   brew install xcodegen
   ```
2. 在本資料夾執行 `xcodegen`，打開產生的 `PayLog.xcodeproj`
3. Xcode → PayLog target → Signing & Capabilities → Team 選你的 Apple ID
4. iPhone 用線接 Mac。iPhone 上開啟：設定 → 隱私權與安全性 → 開發者模式
5. Xcode 上方選你的 iPhone → 按 ▶
6. 第一次執行時，iPhone：設定 → 一般 → VPN 與裝置管理 → 信任你的開發者帳號

用免費 Apple ID 安裝的 App **7 天後會失效**，屆時重按 ▶ 即可。付費開發者帳號（US$99/年）可用 TestFlight 長期安裝，或上架 App Store。

沒有 Mac 的替代方案：租用雲端 Mac（如 MacinCloud），或用 Codemagic / Xcode Cloud 等雲端建置服務，搭配付費開發者帳號發佈到 TestFlight。

## 上架 App Store

1. 加入 Apple Developer Program（US$99/年）
2. App Store Connect 建立 App，Bundle ID 對應 `tw.clmchiu.paylog`（可在 `project.yml` 改）
3. Xcode → Product → Archive → Distribute App → App Store Connect
4. 商店資訊、截圖（6.9 吋 iPhone 至少 1 張）、隱私權政策網址
5. App 隱私問卷：位置（精確位置，用於 App 功能，未連結身分、不追蹤）、購買記錄（同上）；資料不離開裝置
6. 審查備註建議寫明：本 App 透過捷徑「交易」自動化觸發，附上設定步驟與「設定 → 模擬一次付款」測試方式

## 測試

App 內「設定 → 模擬一次付款（測試）」會建立一筆 NT$85 的待填寫紀錄，並打開填寫畫面。
