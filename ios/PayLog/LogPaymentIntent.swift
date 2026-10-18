import AppIntents
import Foundation
import SwiftData
import UserNotifications

/// 「記錄付款」動作：由「捷徑 → 自動化 → 交易」在 Apple Pay 感應付款時觸發，
/// 建立一筆待填寫紀錄並打開 App 的填寫畫面。
struct LogPaymentIntent: AppIntent {
    static var title: LocalizedStringResource = "記錄付款"
    static var description = IntentDescription("建立一筆消費並打開嗶記帳的填寫畫面。搭配「捷徑 → 自動化 → 交易」，Apple Pay 感應付款後自動執行。")
    static var openAppWhenRun: Bool = true

    @Parameter(title: "金額", description: "交易的「數量／金額」")
    var amount: String?

    @Parameter(title: "商家", description: "交易的「商家」")
    var merchant: String?

    @Parameter(title: "卡片", description: "交易的「卡片或票卡」名稱，或直接輸入 VISA / MASTER / 悠遊卡")
    var cardName: String?

    @MainActor
    func perform() async throws -> some IntentResult {
        let e = Expense(
            date: Date(),
            amount: Parser.amount(amount) ?? 0,
            card: Parser.card(cardName) ?? "",
            merchant: (merchant ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
            source: "Apple Pay",
            pending: true
        )
        let ctx = Store.container.mainContext
        ctx.insert(e)
        try? ctx.save()

        Router.shared.open(e.uid)
        Reminder.schedule(for: e)
        return .result()
    }
}

/// 讓「記錄付款」出現在捷徑 App 與 Siri。
struct PayLogShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: LogPaymentIntent(),
            phrases: [
                "用\(.applicationName)記一筆",
                "\(.applicationName)記帳"
            ],
            shortTitle: "記一筆",
            systemImageName: "creditcard"
        )
    }
}

/// App 內導覽：要打開哪一筆紀錄。
@MainActor
final class Router: ObservableObject {
    static let shared = Router()
    @Published var openID: UUID?

    func open(_ uid: UUID) {
        openID = uid
    }
}

/// 備援通知：自動化若無法打開 App（例如鎖定中），仍會跳出提醒。
enum Reminder {
    static func schedule(for e: Expense) {
        let content = UNMutableNotificationContent()
        content.title = e.amount > 0 ? "剛剛付款 \(Parser.money(e.amount))，記一下？" : "剛剛有一筆付款，記一下？"
        var body = "點此填寫用途"
        if !e.card.isEmpty { body += " · \(e.card)" }
        if !e.merchant.isEmpty { body += " · \(e.merchant)" }
        content.body = body
        content.sound = .default
        content.userInfo = ["uid": e.uid.uuidString]
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 2, repeats: false)
        let req = UNNotificationRequest(identifier: e.uid.uuidString, content: content, trigger: trigger)
        UNUserNotificationCenter.current().add(req)
    }

    static func cancel(_ uid: UUID) {
        let c = UNUserNotificationCenter.current()
        c.removePendingNotificationRequests(withIdentifiers: [uid.uuidString])
        c.removeDeliveredNotifications(withIdentifiers: [uid.uuidString])
    }
}
