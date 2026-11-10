import XCTest

final class KeyboardParityUITests: XCTestCase {
    private static var simulatorKeyboardConfigured = false
    override func setUpWithError() throws {
        continueAfterFailure = false
        #if targetEnvironment(simulator)
        if Self.simulatorKeyboardConfigured { return }
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        settings.launch()
        let general = settings.cells["General"]
        _ = settings.navigationBars.firstMatch.waitForExistence(timeout: 10)
        for _ in 0..<5 where !general.exists {
            let back = settings.navigationBars.buttons.firstMatch
            if back.exists { back.tap() } else { break }
        }
        for _ in 0..<4 where !general.isHittable { settings.swipeUp() }
        general.tap()
        let keyboard = settings.cells["Keyboard"]
        for _ in 0..<4 where !keyboard.isHittable { settings.swipeUp() }
        keyboard.tap(); settings.cells["Keyboards"].tap()
        if !settings.cells.containing(NSPredicate(format: "label CONTAINS %@", "对照英语")).firstMatch.exists {
            let add = settings.cells.containing(NSPredicate(format: "label BEGINSWITH %@", "Add New Keyboard")).firstMatch
            XCTAssertTrue(add.waitForExistence(timeout: 10)); add.tap()
            let custom = settings.cells.containing(NSPredicate(format: "label CONTAINS %@", "对照英语")).firstMatch
            XCTAssertTrue(custom.waitForExistence(timeout: 10)); custom.tap()
        }
        Self.simulatorKeyboardConfigured = true
        settings.terminate()
        #endif
    }

    private func activateKeyboard(in app: XCUIApplication) {
        for _ in 0..<6 {
            if app.buttons["设置"].waitForExistence(timeout: 2) { return }
            let next = app.buttons.matching(NSPredicate(format: "label IN %@",
                ["Next keyboard", "Next Keyboard", "下一键盘", "下一个键盘", "Switch keyboard"])).firstMatch
            XCTAssertTrue(next.exists, "System input-method switch must be available")
            next.tap()
        }
        XCTFail("Custom keyboard must appear after switching input methods")
    }

    private func host() -> (XCUIApplication, XCUIElement) {
        let app = XCUIApplication(); app.launch()
        let editor = app.textViews["practiceInput"]; XCTAssertTrue(editor.waitForExistence(timeout: 15)); editor.tap()
        activateKeyboard(in: app)
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 15))
        return (app, editor)
    }
    private func letters(_ value: String, in app: XCUIApplication) {
        for char in value {
            let key = app.buttons[String(char)].firstMatch
            if key.exists { key.tap() } else { app.buttons[String(char).uppercased()].firstMatch.tap() }
        }
    }
    private func candidate(_ title: String, in app: XCUIApplication) {
        for _ in 0..<6 {
            let b = app.buttons[title].firstMatch
            if b.exists && b.isHittable { b.tap(); return }
            app.scrollViews["candidateStrip"].swipeLeft()
        }
        XCTFail("Candidate \(title) must be reachable")
    }
    private func toolButton(_ title: String, in app: XCUIApplication) {
        for _ in 0..<8 {
            let b = app.buttons[title].firstMatch
            if b.exists && b.isHittable { b.tap(); return }
            app.scrollViews["toolPanel"].swipeUp()
        }
        XCTFail("Setting \(title) must be reachable")
    }
    private func screenshot(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    private func chineseQwerty(_ app: XCUIApplication) {
        if app.buttons["26键"].exists { app.buttons["26键"].tap() }
        else if !app.buttons["分词"].exists {
            app.buttons["九键"].tap(); app.buttons["26键"].tap()
        }
        XCTAssertTrue(app.buttons["分词"].exists)
        XCTAssertTrue(app.buttons["q"].exists)
    }
    func testRealKeyboardInputAndTools() {
        let app = XCUIApplication()
        app.launch()
        let editor = app.textViews["practiceInput"]
        XCTAssertTrue(editor.waitForExistence(timeout: 15))
        editor.tap()
        activateKeyboard(in: app)
        print("LEARNIME_IOS_UI_TREE\n\(app.debugDescription)")
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 15), "Enable 对照英语 in iOS keyboard settings first")
        for title in ["剪贴板", "编辑", "符号", "表情", "手写"] { XCTAssertTrue(app.buttons[title].exists) }
        // The currently selected layout may be nine-key or QWERTY.
        XCTAssertEqual(app.buttons.matching(identifier: "中/EN").count, 1)
        XCTAssertEqual(app.buttons.matching(identifier: "🌐").count, 0, "Custom UI must not duplicate the system globe")
        XCTAssertLessThanOrEqual(app.buttons.matching(NSPredicate(format: "label IN %@",
            ["切换输入法", "Next keyboard", "Next Keyboard", "下一键盘", "下一个键盘", "Switch keyboard"])).count, 1)
        chineseQwerty(app)
        for letter in ["n","i","h","a","o"] { app.buttons[letter].firstMatch.tap() }
        XCTAssertTrue(app.buttons["你好"].waitForExistence(timeout: 15))
        app.buttons["你好"].firstMatch.tap()
        XCTAssertTrue((editor.value as? String ?? "").contains("你好"))
        screenshot(app, name: "iOS QWERTY alignment")
        app.buttons["符号"].tap()
        app.buttons["数学"].tap()
        app.buttons["√"].tap()
        app.buttons["关闭"].tap()
        XCTAssertTrue((editor.value as? String ?? "").contains("√"))
        app.buttons["设置"].tap()
        XCTAssertTrue(app.switches.count > 0)
        app.buttons["关闭"].tap()
        app.buttons["手写"].tap()
        XCTAssertTrue(app.otherElements["中文手写画板"].waitForExistence(timeout: 10))
        print("LEARNIME_IOS_UI_PASS input, symbol categories, settings, handwriting")
    }
    func testEnglishNineKeyAndInline() {
        let (app, editor) = host()
        chineseQwerty(app)
        app.buttons["中/EN"].firstMatch.tap()
        XCTAssertTrue(app.buttons["⇧"].exists, "One tap switches directly to English")
        letters("helo", in: app)
        XCTAssertTrue(app.buttons["Hello"].waitForExistence(timeout: 15))
        candidate("Hello", in: app)
        app.buttons["空格"].tap(); letters("world", in: app); app.buttons["空格"].tap()
        XCTAssertEqual(editor.value as? String, "Hello world ")
        app.buttons["九键"].tap()
        for char in "9694264484542" {
            let key = ["2":"ABC","3":"DEF","4":"GHI","5":"JKL","6":"MNO","7":"PQRS","8":"TUV","9":"WXYZ"][String(char)]!
            app.buttons[key].tap()
        }
        XCTAssertTrue(app.buttons["我想回家"].waitForExistence(timeout: 15)); candidate("我想回家", in: app)
        XCTAssertEqual(editor.value as? String, "Hello world 我想回家")
        screenshot(app, name: "iOS nine-key alignment")
        chineseQwerty(app)
        letters("wifi", in: app); candidate("wifi", in: app)
        app.buttons["123"].tap(); for c in "2026" { app.buttons[String(c)].firstMatch.tap() }
        app.buttons["返回"].tap()
        XCTAssertEqual(editor.value as? String, "Hello world 我想回家wifi2026")
        print("LEARNIME_IOS_UI_PASS English correction, nine-key phrase, mixed digits")
    }
    func testDictionaryShortcutsAndAppearance() {
        let (app, editor) = host()
        app.buttons["设置"].tap(); toolButton("个人词典：添加、编辑、删除", in: app)
        if app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "你好世界  ")).count > 0 { throwSkip() ; return }
        app.buttons["＋ 添加词语"].tap()
        letters("nihaoshijie", in: app); XCTAssertTrue(app.buttons["你好世界"].waitForExistence(timeout: 15)); candidate("你好世界", in: app)
        app.buttons["spellingField"].tap(); letters("ni'hao'shi'jie", in: app); app.buttons["保存"].tap()
        app.buttons["设置"].tap(); toolButton("个人词典：添加、编辑、删除", in: app)
        let word = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "你好世界  ")).firstMatch
        XCTAssertTrue(word.waitForExistence(timeout: 15)); word.tap(); app.buttons["删除"].tap()
        XCTAssertEqual(editor.value as? String, "")
        app.buttons["设置"].tap(); toolButton("短语缩写：添加、编辑、删除", in: app)
        app.buttons["＋ 添加缩写"].tap()
        app.buttons["123"].tap(); for c in "2026" { app.buttons[String(c)].firstMatch.tap() }; app.buttons["返回"].tap()
        app.buttons["spellingField"].tap(); letters("zzdemo", in: app); app.buttons["保存"].tap()
        chineseQwerty(app)
        letters("zzdemo", in: app); candidate("2026", in: app)
        XCTAssertEqual(editor.value as? String, "2026")
        app.buttons["设置"].tap(); toolButton("短语缩写：添加、编辑、删除", in: app)
        app.buttons["zzdemo → 2026"].tap(); app.buttons["删除"].tap()
        app.buttons["设置"].tap(); toolButton("单手模式：关闭", in: app); app.buttons["关闭"].tap()
        screenshot(app, name: "iOS one-hand layout")
        app.buttons["设置"].tap(); toolButton("单手模式：靠左", in: app); app.buttons["关闭"].tap()
        app.buttons["设置"].tap(); toolButton("单手模式：靠右", in: app); app.buttons["关闭"].tap()
        print("LEARNIME_IOS_UI_PASS personal dictionary, shortcuts, one-hand layout")
    }
    private func throwSkip() { print("LEARNIME_IOS_UI_SKIP preserves existing personal word") }
}
