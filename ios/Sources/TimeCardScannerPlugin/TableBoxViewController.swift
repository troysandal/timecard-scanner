import TimeCardScannerCore
import UIKit

/// Full-screen "drag the box over the checkpoint rows" step shown after the document scanner, so the saved photo can
/// be the whole card while only the table is read. The user drags a corner to resize, or the inside to move, then
/// taps Read; Cancel closes it. The box starts where the automatic read found the table (or a default).
@MainActor
final class TableBoxViewController: UIViewController {
    enum Outcome {
        case confirmed(ScanRegion)
        case cancelled
    }

    private let image: UIImage
    private var box: CGRect // fractions of the photo: x, y, width, height
    private var completion: ((Outcome) -> Void)?

    private let imageView = UIImageView()
    private let overlay = BoxOverlayView()
    private let titleLabel = UILabel()
    private let readButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)

    init(image: UIImage, initial: ScanRegion, completion: @escaping (Outcome) -> Void) {
        self.image = image
        self.box = CGRect(x: initial.x, y: initial.y, width: initial.width, height: initial.height)
        self.completion = completion
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        imageView.image = image
        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(imageView)

        overlay.translatesAutoresizingMaskIntoConstraints = false
        overlay.imageSize = image.size
        overlay.box = box
        overlay.onChange = { [weak self] in self?.box = $0 }
        view.addSubview(overlay)

        titleLabel.text = Self.string("Drag the box over the checkpoint rows")
        titleLabel.textColor = .white
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 2
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(titleLabel)

        cancelButton.setTitle(Self.string("Cancel"), for: .normal)
        cancelButton.addTarget(self, action: #selector(cancel), for: .touchUpInside)
        readButton.setTitle(Self.string("Read"), for: .normal)
        readButton.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        readButton.addTarget(self, action: #selector(read), for: .touchUpInside)
        let buttons = UIStackView(arrangedSubviews: [cancelButton, readButton])
        buttons.distribution = .equalSpacing
        buttons.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(buttons)

        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            titleLabel.topAnchor.constraint(equalTo: guide.topAnchor, constant: 12),
            titleLabel.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 16),
            titleLabel.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -16),
            buttons.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 24),
            buttons.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -24),
            buttons.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -12),
            buttons.heightAnchor.constraint(equalToConstant: 44),
            imageView.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 12),
            imageView.bottomAnchor.constraint(equalTo: buttons.topAnchor, constant: -12),
            imageView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            overlay.topAnchor.constraint(equalTo: imageView.topAnchor),
            overlay.bottomAnchor.constraint(equalTo: imageView.bottomAnchor),
            overlay.leadingAnchor.constraint(equalTo: imageView.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: imageView.trailingAnchor),
        ])
    }

    /// Strings are looked up in the host app's `Localizable.strings` first, so a host can translate them;
    /// English otherwise.
    private static func string(_ key: String) -> String {
        NSLocalizedString(key, tableName: nil, bundle: .main, value: key, comment: "")
    }

    private func finish(_ outcome: Outcome) {
        guard let done = completion else { return }
        completion = nil
        dismiss(animated: true) { done(outcome) }
    }

    @objc private func cancel() { finish(.cancelled) }

    @objc private func read() {
        guard let region = ScanRegion(x: box.minX, y: box.minY, width: box.width, height: box.height) else { return }
        finish(.confirmed(region))
    }
}

/// Draws the dimmed surround and the box with its four corner handles, over the aspect-fit photo, and turns drags
/// into a new box (fractions of the photo). Corner drags resize, a drag inside moves.
@MainActor
final class BoxOverlayView: UIView {
    var imageSize: CGSize = .zero
    var onChange: ((CGRect) -> Void)?
    var box: CGRect = .zero { didSet { setNeedsDisplay() } }

    private enum Drag { case corner(Int), move }
    private var drag: Drag?
    private var dragStart = CGRect.zero
    private let minSide: CGFloat = CGFloat(ScanRegion.minSide)
    private let handleRadius: CGFloat = 14
    private let grab: CGFloat = 44

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(pan(_:))))
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    /// Where the photo is drawn inside this view (aspect fit).
    private var photoRect: CGRect {
        guard imageSize.width > 0, imageSize.height > 0, bounds.width > 0, bounds.height > 0 else { return bounds }
        let scale = min(bounds.width / imageSize.width, bounds.height / imageSize.height)
        let w = imageSize.width * scale, h = imageSize.height * scale
        return CGRect(x: (bounds.width - w) / 2, y: (bounds.height - h) / 2, width: w, height: h)
    }

    private var boxRect: CGRect {
        let p = photoRect
        return CGRect(x: p.minX + box.minX * p.width, y: p.minY + box.minY * p.height, width: box.width * p.width, height: box.height * p.height)
    }

    private func corners(of r: CGRect) -> [CGPoint] {
        [CGPoint(x: r.minX, y: r.minY), CGPoint(x: r.maxX, y: r.minY), CGPoint(x: r.maxX, y: r.maxY), CGPoint(x: r.minX, y: r.maxY)]
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let r = boxRect
        ctx.setFillColor(UIColor.black.withAlphaComponent(0.55).cgColor)
        ctx.addRect(photoRect)
        ctx.addRect(r)
        ctx.fillPath(using: .evenOdd)
        ctx.setStrokeColor(UIColor.systemYellow.cgColor)
        ctx.setLineWidth(2)
        ctx.stroke(r)
        ctx.setFillColor(UIColor.systemYellow.cgColor)
        for c in corners(of: r) {
            ctx.fillEllipse(in: CGRect(x: c.x - handleRadius, y: c.y - handleRadius, width: handleRadius * 2, height: handleRadius * 2))
        }
    }

    @objc private func pan(_ g: UIPanGestureRecognizer) {
        let p = photoRect
        guard p.width > 0, p.height > 0 else { return }
        switch g.state {
        case .began:
            let at = g.location(in: self)
            let r = boxRect
            if let i = corners(of: r).enumerated().min(by: { hypot($0.element.x - at.x, $0.element.y - at.y) < hypot($1.element.x - at.x, $1.element.y - at.y) }),
               hypot(i.element.x - at.x, i.element.y - at.y) <= grab {
                drag = .corner(i.offset)
            } else if r.contains(at) {
                drag = .move
            } else {
                drag = nil
            }
            dragStart = box
        case .changed:
            guard let drag else { return }
            let t = g.translation(in: self)
            let dx = t.x / p.width, dy = t.y / p.height
            var b = dragStart
            switch drag {
            case .move:
                b.origin.x = min(max(0, dragStart.minX + dx), 1 - b.width)
                b.origin.y = min(max(0, dragStart.minY + dy), 1 - b.height)
            case .corner(let i):
                var x0 = dragStart.minX, y0 = dragStart.minY, x1 = dragStart.maxX, y1 = dragStart.maxY
                if i == 0 || i == 3 { x0 = min(max(0, x0 + dx), x1 - minSide) } else { x1 = max(min(1, x1 + dx), x0 + minSide) }
                if i == 0 || i == 1 { y0 = min(max(0, y0 + dy), y1 - minSide) } else { y1 = max(min(1, y1 + dy), y0 + minSide) }
                b = CGRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
            }
            box = b
            onChange?(b)
        default:
            drag = nil
        }
    }
}
