import Foundation
import Shared

enum KeyboardPreferences {
    static let store = UserDefaults.standard
    static func register() {
        if let previous = store.object(forKey: "pinyinCorrection") as? Bool, store.object(forKey: "autoCorrection") == nil {
            store.set(previous, forKey: "autoCorrection")
        }
        store.register(defaults: [
            "translation": true, "translationModel": "m2m100", "learning": true, "prediction": true, "mixed": true,
            "autoCorrection": true, "englishSuggestions": true, "autoCap": true,
            "keyHeight": 44, "keyFont": 22, "candidateFont": 20, "theme": "system",
            "oneHand": "off", "handwritingSpeed": "normal", "handwritingThickness": 3, "longPress": 400
        ])
    }
}

/** Swift only loads bundled data; all dictionary behavior is in Shared. */
final class EnglishDictionary {
    private let dictionary: EnglishLexicon
    init() {
        dictionary = EnglishLexicon(text: Self.resource("english-frequency"))
    }
    fileprivate static func resource(_ name: String) -> String {
        guard let url = Bundle.main.url(forResource: name, withExtension: "txt", subdirectory: "extras") else { return "" }
        return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
    }
    func suggestions(_ input: String) -> [String] { dictionary.suggestions(input: input) }
    func correction(_ input: String) -> String { dictionary.correction(input: input) }
    func nine(_ input: String) -> [String] { dictionary.nineSuggestions(input: input) }
}

final class TraditionalConverter {
    private let converter = TraditionalText(characters: EnglishDictionary.resource("STCharacters"), phrases: EnglishDictionary.resource("STPhrases"))
    func convert(_ text: String) -> String { converter.convert(text: text) }
}

struct ClipboardEntry: Codable {
    var text: String, date: Date, pinned: Bool
    var shared: ClipboardRecord { ClipboardRecord(text: text, timestamp: Int64(date.timeIntervalSince1970 * 1000), pinned: pinned) }
    init(_ shared: ClipboardRecord) {
        text = shared.text; date = Date(timeIntervalSince1970: Double(shared.timestamp) / 1000); pinned = shared.pinned
    }
}
/** Swift owns Codable/UserDefaults; shared policy owns expiry, ranking and limits. */
final class ClipboardHistory {
    private let key = "clipboardEntries"
    private func records() -> [ClipboardRecord] {
        let data = UserDefaults.standard.data(forKey: key) ?? Data()
        return ((try? JSONDecoder().decode([ClipboardEntry].self, from: data)) ?? []).map(\.shared)
    }
    func entries() -> [ClipboardEntry] {
        let result = ClipboardPolicy.shared.retained(entries: records(), now: Int64(Date().timeIntervalSince1970 * 1000))
        save(result); return result.map(ClipboardEntry.init)
    }
    func add(_ text: String) { save(ClipboardPolicy.shared.added(entries: records(), text: text, now: Int64(Date().timeIntervalSince1970 * 1000))) }
    func pin(_ text: String) {
        save(entries().map { ClipboardRecord(text: $0.text, timestamp: $0.shared.timestamp, pinned: $0.text == text ? !$0.pinned : $0.pinned) })
    }
    func delete(_ text: String) { save(entries().filter { $0.text != text }.map(\.shared)) }
    func clear() { UserDefaults.standard.removeObject(forKey: key) }
    private func save(_ records: [ClipboardRecord]) {
        UserDefaults.standard.set(try? JSONEncoder().encode(records.map(ClipboardEntry.init)), forKey: key)
    }
}
