import SwiftUI

@main struct LearnIMEApp: App {
    var body: some Scene { WindowGroup { SetupView() } }
}

struct SetupView: View {
    @State private var practice = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("本地输入与翻译") {
                    Text("支持中文26键、九键、英文候选、简繁输出与单字连续手写。默认使用 M2M100 离线中英互译，在键盘中查看对照翻译。")
                    Text("模型和输入内容留在本机。")
                }
                Section("启用键盘") {
                    Text("系统设置 → 通用 → 键盘 → 键盘 → 添加新键盘 → 对照英语")
                    Text("在键盘工具栏点「设置」，管理个人词典、短语缩写、模糊音、学习联想、主题、单手布局和按键反馈。")
                    Text("剪贴板通过系统粘贴按钮读取；复制选中文字需自愿开启完全访问。iOS 键盘扩展不能录音，请使用系统听写。")
                    Button("打开系统设置") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }
                Section("练习区") { TextEditor(text: $practice).frame(height: 160).accessibilityIdentifier("practiceInput") }
            }
            .navigationTitle("对照英语")
            .onAppear {
                #if DEBUG
                if let text = ProcessInfo.processInfo.environment["LEARNIME_PRACTICE_TEXT"] { practice = text }
                #endif
            }
        }
    }
}
