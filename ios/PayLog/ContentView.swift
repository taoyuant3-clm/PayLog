import SwiftData
import SwiftUI

struct SheetItem: Identifiable {
    let expense: Expense
    let isNew: Bool
    var id: UUID { expense.uid }
}

/// 主畫面：本月合計、待填寫、消費清單、匯出。
struct ContentView: View {
    @Environment(\.modelContext) private var context
    @EnvironmentObject private var router: Router
    @Query(sort: \Expense.date, order: .reverse) private var items: [Expense]

    @State private var sheet: SheetItem?
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            List {
                Section { summary }

                if items.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("還沒有紀錄").font(.headline)
                            Text("到「設定」照步驟建立捷徑自動化，之後用 Apple Pay 感應付款就會自動跳出填寫畫面。")
                                .font(.subheadline).foregroundStyle(.secondary)
                            Button("查看設定步驟") { showSettings = true }
                                .padding(.top, 4)
                        }
                        .padding(.vertical, 6)
                    }
                } else {
                    Section("最近紀錄") {
                        ForEach(items) { e in
                            Button { sheet = SheetItem(expense: e, isNew: false) } label: { row(e) }
                                .buttonStyle(.plain)
                        }
                        .onDelete(perform: delete)
                    }
                }
            }
            .navigationTitle("嗶記帳")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("設定")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    ShareLink(item: CSVFile(text: CSV.build(items)),
                              preview: SharePreview("嗶記帳.csv")) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .disabled(items.isEmpty)
                    .accessibilityLabel("匯出 CSV")
                }
                ToolbarItem(placement: .bottomBar) {
                    Button {
                        sheet = SheetItem(expense: Expense(source: "", pending: true), isNew: true)
                    } label: {
                        Label("記一筆", systemImage: "plus.circle.fill").font(.headline)
                    }
                }
            }
            .sheet(item: $sheet) { item in
                NavigationStack {
                    RecordView(expense: item.expense, isNew: item.isNew)
                }
                .presentationDragIndicator(.visible)
                .interactiveDismissDisabled(item.isNew)
            }
            .sheet(isPresented: $showSettings) {
                NavigationStack { SettingsView() }
            }
            .onAppear(perform: openRouted)
            .onChange(of: router.openID) { _, _ in openRouted() }
        }
    }

    // MARK: - 本月合計

    private var monthItems: [Expense] {
        let cal = Calendar.current
        guard let start = cal.date(from: cal.dateComponents([.year, .month], from: Date())),
              let end = cal.date(byAdding: .month, value: 1, to: start) else { return [] }
        return items.filter { !$0.pending && $0.date >= start && $0.date < end }
    }

    private var summary: some View {
        let month = monthItems
        let total = month.reduce(0) { $0 + $1.amount }
        let pending = items.filter(\.pending).count
        return VStack(alignment: .leading, spacing: 10) {
            Text(Date.now.formatted(.dateTime.year().month(.wide)) + " 支出")
                .font(.subheadline).foregroundStyle(.secondary)
            Text(Parser.money(total))
                .font(.system(size: 36, weight: .bold, design: .rounded))
            HStack {
                ForEach(Cards.all, id: \.self) { card in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(card).font(.caption).foregroundStyle(.secondary)
                        Text(Parser.money(month.filter { $0.card == card }.reduce(0) { $0 + $1.amount }))
                            .font(.subheadline.bold())
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if pending > 0 {
                Label("有 \(pending) 筆付款尚未填寫", systemImage: "exclamationmark.triangle.fill")
                    .font(.subheadline.bold())
                    .foregroundStyle(.orange)
            }
        }
        .padding(.vertical, 6)
    }

    // MARK: - 清單列

    private func row(_ e: Expense) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(e.date.formatted(.dateTime.month(.twoDigits).day(.twoDigits).hour().minute())
                     + (e.card.isEmpty ? "" : "  ·  \(e.card)"))
                    .font(.footnote).foregroundStyle(.secondary)
                Spacer()
                Text(e.amount > 0 ? Parser.money(e.amount) : "NT$ —")
                    .font(.headline)
            }
            if e.pending {
                Text("⚠ 待填寫 — 點此補填")
                    .font(.subheadline).foregroundStyle(.orange)
            } else {
                Text([e.category, e.note, e.merchant, e.address].filter { !$0.isEmpty }.joined(separator: "  ·  "))
                    .font(.subheadline).foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .contentShape(Rectangle())
        .padding(.vertical, 2)
    }

    // MARK: - 動作

    private func delete(_ offsets: IndexSet) {
        for i in offsets {
            Reminder.cancel(items[i].uid)
            context.delete(items[i])
        }
        try? context.save()
    }

    private func openRouted() {
        guard let uid = router.openID else { return }
        router.openID = nil
        if let e = Store.find(uid) {
            sheet = SheetItem(expense: e, isNew: false)
        }
    }
}
