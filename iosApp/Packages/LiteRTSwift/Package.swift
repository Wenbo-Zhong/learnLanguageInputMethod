// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "LiteRT",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "TensorFlowLite", targets: ["TensorFlowLite"]),
        .library(name: "LiteRT", targets: ["LiteRT"]),
        .library(name: "LiteRtMetalAccelerator", targets: ["LiteRtMetalAccelerator"])
    ],
    targets: [
        .binaryTarget(
            name: "CLiteRT",
            url: "https://github.com/google-ai-edge/LiteRT/releases/download/v2.3.0/CLiteRT.xcframework.zip",
            checksum: "b36a1a4f1b3eeb04546b4cc2986902cd704439b37c34597ab0f804aed08f5a00"
        ),
        .binaryTarget(
            name: "LiteRtMetalAccelerator",
            url: "https://github.com/google-ai-edge/LiteRT/releases/download/v2.3.0/LiteRtMetalAccelerator.xcframework.zip",
            checksum: "e564e6678959098a35c1dbcffc60041afac25a845abcee998a53f958e342aeec"
        ),
        .target(name: "LiteRT", dependencies: ["CLiteRT"], path: "Sources/LiteRT"),
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
