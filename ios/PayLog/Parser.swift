import Foundation

/// 解析「捷徑 → 交易」自動化傳進來的金額與卡片名稱。
enum Parser {

    /// "NT$1,250.00"、"1250"、"$85" → 1250 / 85
    static func amount(_ text: String?) -> Double? {
        guard let text, !text.isEmpty else { return nil }
        let pattern = #"[0-9][0-9,]*(?:\.[0-9]+)?"#
        guard let range = text.range(of: pattern, options: .regularExpression) else { return nil }
        let number = text[range].replacingOccurrences(of: ",", with: "")
        guard let v = Double(number), v > 0 else { return nil }
        return v
    }

    /// 卡片名稱 → VISA / MASTER / 悠遊卡；看不出來回傳 nil
    static func card(_ name: String?) -> String? {
        guard let s = name?.lowercased(), !s.isEmpty else { return nil }
        if s.contains("悠遊") || s.contains("easycard") || s.contains("easy card") { return Cards.easyCard }
        if s.contains("master") || s.contains("萬事達") { return Cards.master }
        if s.contains("visa") { return Cards.visa }
        return nil
    }

    static func money(_ v: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.maximumFractionDigits = v == v.rounded() ? 0 : 2
        return "NT$" + (f.string(from: NSNumber(value: v)) ?? String(v))
    }

    static func plain(_ v: Double) -> String {
        v == v.rounded() ? String(Int64(v)) : String(v)
    }
}
