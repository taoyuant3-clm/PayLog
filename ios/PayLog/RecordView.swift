import SwiftData
import SwiftUI

/// 填寫消費內容：
/// 使用者填 — 消費總值、支付卡（VISA / MASTER / 悠遊卡）、用途；
/// 自動帶入 — 日期、時間、地點（定位反查）、商家（Apple Pay 交易）。
struct RecordView: View {
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss

    @Bindable var expense: Expense
    let isNew: Bool

    @AppStorage("lastCard") private var lastCard = ""
    @AppStorage("lastCategory") private var lastCategory = ""

    @StateObject private var location = LocationService()
    @State private var amountText = ""
    @State private var autoAddress = ""
    @State private var showDeleteConfirm = false
    @State private var errorText: String?
    @FocusState private var amountFocused: Bool

    var body: some View {
        Form {
            Section {
                TextField("0", text: $amountText)
                    .keyboardType(.decimalPad)
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .focused($amountFocused)
            } header: {
                Text("消費總值（NT$）")
            } footer: {
                if let errorText { Text(errorText).foregroundStyle(.red) }
            }

            Section("支付卡") {
                Picker("支付卡", selection: $expense.card) {
                    ForEach(Cards.all, id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.segmented)
            }

            Section("用途") {
                Picker("分類", selection: $expense.category) {
                    ForEach(Categories.all, id: \.self) { Text($0).tag($0) }
                }
                TextField("備註（選填），例如：午餐、出差計程車", text: $expense.note)
            }

            Section {
                DatePicker("日期時間", selection: $expense.date)
                LabeledContent("商家") {
                    TextField("例如：7-ELEVEN", text: $expense.merchant)
                        .multilineTextAlignment(.trailing)
                }
                HStack(alignment: .top) {
                    TextField("地點", text: $expense.address, axis: .vertical)
                        .lineLimit(1...3)
                    Button {
                        autoAddress = ""
                        expense.address = ""
                        location.locate()
                    } label: {
                        Image(systemName: "location.circle")
                            .imageScale(.large)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("重新定位")
                }
            } header: {
                Text("自動帶入（可修改）")
            } footer: {
                if !location.status.isEmpty { Text("定位：\(location.status)") }
            }

            if !isNew {
                Section {
                    Button(expense.pending ? "略過這筆" : "刪除這筆", role: .destructive) {
                        showDeleteConfirm = true
                    }
                }
            }
        }
        .navigationTitle(expense.pending ? "記錄這筆消費" : "編輯消費")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("儲存") { save() }.bold()
            }
        }
        .confirmationDialog(expense.pending ? "略過這筆付款，不記錄？" : "刪除這筆紀錄？",
                            isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button(expense.pending ? "略過" : "刪除", role: .destructive) { delete() }
        }
        .onAppear(perform: setUp)
        .onReceive(location.$address) { value in
            guard let value else { return }
            let current = expense.address.trimmingCharacters(in: .whitespacesAndNewlines)
            if current.isEmpty || current == autoAddress {
                expense.address = value
                autoAddress = value
            }
        }
        .onReceive(location.$coordinate) { c in
            guard let c else { return }
            expense.latitude = c.latitude
            expense.longitude = c.longitude
        }
    }

    private func setUp() {
        Reminder.cancel(expense.uid)
        if expense.amount > 0 { amountText = Parser.plain(expense.amount) }
        if expense.card.isEmpty && (isNew || expense.pending) { expense.card = lastCard }
        if expense.category.isEmpty { expense.category = lastCategory.isEmpty ? Categories.all[0] : lastCategory }
        // 只對新紀錄 / 待填寫紀錄自動定位；編輯舊紀錄時不覆蓋原地點
        if (isNew || expense.pending) && expense.latitude == nil && expense.address.isEmpty {
            location.locate()
        }
        if expense.amount <= 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { amountFocused = true }
        }
    }

    private func save() {
        let cleaned = amountText.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces)
        guard let v = Double(cleaned), v > 0 else {
            errorText = "請輸入消費總值"
            amountFocused = true
            return
        }
        guard Cards.all.contains(expense.card) else {
            errorText = "請選擇支付卡"
            return
        }
        expense.amount = (v * 100).rounded() / 100
        expense.note = expense.note.trimmingCharacters(in: .whitespacesAndNewlines)
        expense.merchant = expense.merchant.trimmingCharacters(in: .whitespacesAndNewlines)
        expense.address = expense.address.trimmingCharacters(in: .whitespacesAndNewlines)
        expense.pending = false
        if isNew { context.insert(expense) }
        try? context.save()
        lastCard = expense.card
        lastCategory = expense.category
        dismiss()
    }

    private func delete() {
        Reminder.cancel(expense.uid)
        context.delete(expense)
        try? context.save()
        dismiss()
    }
}
