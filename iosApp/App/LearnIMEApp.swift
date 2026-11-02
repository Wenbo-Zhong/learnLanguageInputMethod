import SwiftUI

@main struct LearnIMEApp: App {
    var body: some Scene { WindowGroup { SetupView() } }
}

struct SetupView: View {
    @State private var practice = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("本地翻译") {
                    Text("OPUS-MT tiny 中译英模型已内置在键盘扩展中。首次使用时加载模型，之后可离线翻译。")
                    Text("模型和输入内容留在本机。")
                }
                Section("启用键盘") {
                    Text("系统设置 → 通用 → 键盘 → 键盘 → 添加新键盘 → 对照英语")
                    Text("支持中文26键、中文九键和英文键盘。输入中文后，停顿片刻即可在键盘中查看英文对照。")
                    Button("打开系统设置") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }
                Section("练习区") { TextEditor(text: $practice).frame(height: 160) }
            }
            .navigationTitle("对照英语")
        }
    }
}
