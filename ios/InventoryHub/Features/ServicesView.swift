import SwiftUI

/// All Services: an accordion of module groups, one open at a time (Android: ServicesScreen).
struct ServicesView: View {
    @Environment(AppModel.self) private var model
    @State private var open: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 10) {
                    Greeting()
                    SectionTitle(text: "All Services")
                    ForEach(Modules.groups(blocked: model.worker?.blockedModules ?? [])) { group in
                        groupCard(group)
                    }
                }
                .padding(16)
            }
            .background(Theme.background)
            .navigationDestination(for: Module.self) { ModuleDestination(module: $0) }
        }
    }

    private func groupCard(_ group: ModuleGroup) -> some View {
        let expanded = open == group.title
        return VStack(spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { open = expanded ? nil : group.title }
            } label: {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: group.icon)
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(group.tint, in: RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(group.title)
                            .font(Theme.font(16, .bold))
                            .foregroundStyle(Theme.text1)
                        Text(group.summary)
                            .font(Theme.font(14))
                            .foregroundStyle(Theme.text3)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .foregroundStyle(Theme.text1)
                        .padding(.top, 6)
                }
                .padding(14)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(expanded ? "Collapse" : "Expand")

            if expanded {
                VStack(spacing: 0) {
                    ForEach(Array(group.modules.enumerated()), id: \.element.id) { index, module in
                        if index > 0 { Divider().padding(.horizontal, 12) }
                        NavigationLink(value: module) {
                            HStack {
                                Text(module.title)
                                    .font(Theme.font(16))
                                    .foregroundStyle(Theme.text1)
                                Spacer()
                                if !module.built {
                                    Text("Soon")
                                        .font(Theme.font(12, .bold))
                                        .foregroundStyle(Theme.text3)
                                }
                            }
                            .frame(minHeight: 48)
                            .padding(.horizontal, 14)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .background(Theme.inset, in: RoundedRectangle(cornerRadius: 8))
                .padding([.horizontal, .bottom], 10)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .background(Color.white, in: RoundedRectangle(cornerRadius: Theme.cardRadius))
    }
}
