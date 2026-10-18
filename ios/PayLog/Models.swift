import Foundation
import SwiftData

/// 一筆消費紀錄。
@Model
final class Expense {
    var uid: UUID = UUID()
    var date: Date = Date()          // 日期時間（自動帶入，可修改）
    var amount: Double = 0           // 消費總值（NT$）
    var card: String = ""            // VISA / MASTER / 悠遊卡
    var category: String = ""        // 用途分類
    var note: String = ""            // 備註
    var merchant: String = ""        // 商家（由 Apple Pay 交易帶入）
    var address: String = ""         // 地點（定位反查）
    var latitude: Double? = nil
    var longitude: Double? = nil
    var source: String = ""          // 例如「Apple Pay」；手動 = ""
    var pending: Bool = true         // true = 已偵測付款、尚未填寫

    init(date: Date = Date(), amount: Double = 0, card: String = "", merchant: String = "",
         source: String = "", pending: Bool = true) {
        self.uid = UUID()
        self.date = date
        self.amount = amount
        self.card = card
        self.merchant = merchant
        self.source = source
        self.pending = pending
    }
}

enum Cards {
    static let visa = "VISA"
    static let master = "MASTER"
    static let easyCard = "悠遊卡"
    static let all = [visa, master, easyCard]
}

enum Categories {
    static let all = ["餐飲", "交通", "日用品", "購物", "娛樂", "醫療", "工作/公務", "其他"]
}

/// App 與 App Intent 共用同一個資料庫。
enum Store {
    @MainActor
    static let container: ModelContainer = {
        do {
            return try ModelContainer(for: Expense.self)
        } catch {
            fatalError("無法建立資料庫：\(error)")
        }
    }()

    @MainActor
    static func find(_ uid: UUID) -> Expense? {
        let d = FetchDescriptor<Expense>(predicate: #Predicate { $0.uid == uid })
        return try? container.mainContext.fetch(d).first
    }
}
