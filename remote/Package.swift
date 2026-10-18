// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TVRemote",
    platforms: [.macOS(.v15)],
    targets: [
        .executableTarget(name: "TVRemote"),
        .testTarget(name: "TVRemoteTests", dependencies: ["TVRemote"]),
    ]
)
