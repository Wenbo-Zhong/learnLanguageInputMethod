// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "LiteRT",
    platforms: [.iOS(.v15)],
    products: [.library(name: "TensorFlowLite", targets: ["TensorFlowLite"])],
    targets: [
        .binaryTarget(
            name: "TensorFlowLiteC",
            url: "https://github.com/google-ai-edge/LiteRT/releases/download/v2.3.0/TensorFlowLiteC.xcframework.zip",
            checksum: "f77274f9249af1e7dd1de88f57661944f80d177ff2c386b28aa3d466b7173f50"
        ),
        .target(
            name: "TensorFlowLite",
            dependencies: ["TensorFlowLiteC"],
            path: "Sources/TensorFlowLite",
            exclude: ["CoreMLDelegate.swift", "MetalDelegate.swift"]
        )
    ]
)
