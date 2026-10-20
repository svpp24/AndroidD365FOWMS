import CoreText
import SwiftUI

/// The Android app's Indigo look (Theme.kt): Nunito Sans, a light grey page, white borderless cards.
enum Theme {
    static let primary = Color(hex: 0x232A7C)
    static let primaryContainer = Color(hex: 0xE1E0F6)
    static let background = Color(hex: 0xF3F3F6)
    static let inset = Color(hex: 0xF2F3F7)
    static let text1 = Color(hex: 0x14183A)
    static let text2 = Color(hex: 0x3E4360)
    static let text3 = Color(hex: 0x5A5F7A)
    static let border = Color(hex: 0xC3C7D8)
    static let green = Color(hex: 0x16A34A)
    static let amber = Color(hex: 0xD97706)
    static let red = Color(hex: 0xDC2626)
    static let scan = Color(hex: 0x1FA99B)

    static let cardRadius: CGFloat = 10

    /// The font's PostScript name, read from the bundled file so it never has to be guessed.
    private static let fontName: String? = {
        guard let url = Bundle.main.url(forResource: "NunitoSans", withExtension: "ttf") else { return nil }
        CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        guard let provider = CGDataProvider(url: url as CFURL), let font = CGFont(provider) else { return nil }
        return font.postScriptName as String?
    }()

    /// Nunito Sans at a size that follows Dynamic Type, or the system font if it failed to load.
    static func font(_ size: CGFloat, _ weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        guard let fontName else { return .system(size: size, weight: weight) }
        return Font.custom(fontName, size: size, relativeTo: style).weight(weight)
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// A white borderless card, as on Android since the 20 Oct restyle.
struct CardBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.white, in: RoundedRectangle(cornerRadius: Theme.cardRadius))
    }
}

extension View {
    func card() -> some View { modifier(CardBackground()) }
}

/// The section title above a group of cards ("All Services", "My Dashboard").
struct SectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .font(Theme.font(18, .bold, relativeTo: .headline))
            .foregroundStyle(Theme.text1)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The full-width filled button.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.font(16, .bold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(
                (isEnabled ? Theme.primary : Theme.border).opacity(configuration.isPressed ? 0.85 : 1),
                in: RoundedRectangle(cornerRadius: Theme.cardRadius)
            )
    }
}

/// A message under a form: what went wrong, in red, wrapping in full (never truncated).
struct ErrorText: View {
    let text: String?

    var body: some View {
        if let text, !text.isEmpty {
            Text(text)
                .font(Theme.font(14))
                .foregroundStyle(Theme.red)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
