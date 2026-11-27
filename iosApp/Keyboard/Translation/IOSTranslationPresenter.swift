import UIKit
import Shared

/// Owns debouncing, request validity and the translation card's native presentation.
final class IOSTranslationPresenter {
    private let layout: IOSKeyboardLayout
    private let engine: LocalEngine
    private let policy = TranslationPolicy()
    private let active: () -> Bool
    private let resize: () -> Void
    private var pending: DispatchWorkItem?
    init(layout: IOSKeyboardLayout, engine: LocalEngine, active: @escaping () -> Bool, resize: @escaping () -> Void) {
        self.layout = layout; self.engine = engine; self.active = active; self.resize = resize
    }
    func reset() { policy.reset(); pending?.cancel(); pending = nil; engine.cancel() }
    func update(context: String, enabled: Bool, model: String, compact: Bool) {
        guard active() else { return }
        let direction = TranslationModels.shared.direction(context: context, model: model)
        guard let request = policy.update(context: context, enabled: enabled, direction: direction,
            model: model, allowModelSwitch: false) else { return }
        pending?.cancel(); engine.cancel()
        guard enabled else {
            layout.source.text = ""; layout.english.text = ""
            layout.translationCard.isHidden = true; return
        }
        let sentence = request.source
        let bilingual = TranslationModels.shared.supports(model: model, direction: .englishToChinese)
        layout.direction.text = direction == .englishToChinese ? "EN → 中" : "中 → EN"
        guard !sentence.isEmpty else {
            layout.source.isHidden = true
            layout.english.text = bilingual ? "输入中文或英文，查看对照翻译" : "输入中文，看看英文怎么说"
            layout.direction.text = bilingual ? "中 ⇄ EN" : "中 → EN"
            layout.status.text = bilingual ? "M2M100 · 离线" : "OPUS-MT · 离线"
            resize(); return
        }
        layout.source.text = sentence; layout.source.isHidden = compact; layout.english.text = "正在翻译…"
        resize(); layout.status.text = "等待停顿…"
        let work = DispatchWorkItem { [weak self] in
            guard let self, active(), policy.isCurrent(request: request) else { return }
            layout.status.text = "正在本地翻译…"
            engine.translate(source: sentence) { [weak self] result in
                guard let self, active(), policy.isCurrent(request: request) else { return }
                switch result {
                case .success(let output): layout.english.text = output; layout.status.text = engine.backendLabel
                case .failure(let error): layout.english.text = error.localizedDescription; layout.status.text = "翻译未完成"
                }
                resize()
            }
        }
        pending = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(request.delayMillis) / 1000, execute: work)
    }
}
