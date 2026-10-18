import SwiftData
import SwiftUI

/// 設定與「捷徑自動化」建立步驟。
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.modelContext) private var context

    var body: some View {
        Form {
            Section {
                Text("iPhone 不允許 App 讀取其他 App 的通知，所以改用 iOS 內建的「捷徑 → 自動化 → 交易」：用 Apple Pay 感應付款的當下，自動執行嗶記帳的「記錄付款」，打開填寫畫面並帶入金額、商家、卡片。")
                    .font(.subheadline)
            } header: {
                Text("運作方式")
            }

            Section {
                step(1, "打開「捷徑」App → 下方「自動化」→ 右上「＋」")
                step(2, "選「交易」→ 勾選要偵測的卡片（VISA、Mastercard…），類別可全選")
                step(3, "選「立即執行」（不要選「執行前先確認」）→「下一步」")
                step(4, "選「新增空白自動化」→「加入動作」→ 搜尋「嗶記帳」→ 點「記錄付款」")
                step(5, "點「金額」→ 選「捷徑輸入」→ 再點它選「數量」")
                step(6, "點「商家」→「捷徑輸入」→ 選「商家」；點「卡片」→「捷徑輸入」→ 選「卡片或票卡」")
                step(7, "完成。下次用 Apple Pay 感應付款就會自動跳出填寫畫面")
                Button {
                    if let url = URL(string: "shortcuts://") { openURL(url) }
                } label: {
                    Label("打開捷徑 App", systemImage: "arrow.up.forward.app")
                }
            } header: {
                Text("建立自動化（只需設定一次）")
            } footer: {
                Text("小提示：如果卡片名稱看不出是 VISA 或 Mastercard（例如「國泰 CUBE 卡」），可以為每張卡各建一個自動化，「卡片」欄直接輸入 VISA 或 MASTER。")
            }

            Section {
                Button {
                    simulate()
                } label: {
                    Label("模擬一次付款（測試）", systemImage: "wave.3.right")
                }
            } footer: {
                Text("會建立一筆 NT$85、VISA、7-ELEVEN 的待填寫紀錄並打開填寫畫面。")
            }

            Section {
                Text("實體悠遊卡、或沒有用 Apple Pay 的付款，請在主畫面下方按「記一筆」手動記錄。")
                    .font(.subheadline)
                Text("所有紀錄只存在這支 iPhone，不會上傳。")
                    .font(.subheadline).foregroundStyle(.secondary)
            } header: {
                Text("其他")
            }
        }
        .navigationTitle("設定")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("完成") { dismiss() }
            }
        }
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(n)")
                .font(.footnote.bold())
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Circle().fill(Color.accentColor))
            Text(text).font(.subheadline)
        }
    }

    private func simulate() {
        let e = Expense(date: Date(), amount: 85, card: Cards.visa, merchant: "7-ELEVEN",
                        source: "測試", pending: true)
        context.insert(e)
        try? context.save()
        dismiss()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            Router.shared.open(e.uid)
        }
    }
}
