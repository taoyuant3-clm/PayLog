import CoreTransferable
import Foundation
import UniformTypeIdentifiers

/// CSV（含 BOM，Excel 開啟中文不亂碼）。
enum CSV {
    static func build(_ rows: [Expense]) -> String {
        let df = DateFormatter()
        df.locale = Locale(identifier: "zh_Hant_TW")
        var s = "\u{FEFF}日期,時間,消費總值,支付卡,用途,備註,商家,地點,緯度,經度,來源,狀態\r\n"
        for r in rows {
            df.dateFormat = "yyyy/MM/dd"
            let d = df.string(from: r.date)
            df.dateFormat = "HH:mm"
            let t = df.string(from: r.date)
            let lat = r.latitude.map { String(format: "%.6f", $0) } ?? ""
            let lng = r.longitude.map { String(format: "%.6f", $0) } ?? ""
            let fields = [d, t, Parser.plain(r.amount), esc(r.card), esc(r.category), esc(r.note),
                          esc(r.merchant), esc(r.address), lat, lng,
                          esc(r.source.isEmpty ? "手動" : r.source), r.pending ? "待填寫" : "已填寫"]
            s += fields.joined(separator: ",") + "\r\n"
        }
        return s
    }

    private static func esc(_ v: String) -> String {
        if v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") {
            return "\"" + v.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return v
    }
}

struct CSVFile: Transferable {
    let text: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .commaSeparatedText) { file in
            let f = DateFormatter()
            f.dateFormat = "yyyyMMdd"
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("嗶記帳_\(f.string(from: Date())).csv")
            try Data(file.text.utf8).write(to: url, options: .atomic)
            return SentTransferredFile(url)
        }
    }
}
