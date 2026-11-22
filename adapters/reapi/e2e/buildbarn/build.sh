#!/bin/sh
# Builds Buildbarn (bb_storage, bb_scheduler, bb_worker, bb_runner) from source with Go, into ./bin.
# For machines where its container images are not an option. Needs git and Go (the toolchain the
# sources ask for is downloaded by Go itself).
set -eu
cd "$(dirname "$0")"
mkdir -p src bin
BIN="$(pwd)/bin"
for repo in bb-storage bb-remote-execution; do
  [ -d "src/$repo" ] || git clone --depth 1 "https://github.com/buildbarn/$repo.git" "src/$repo"
done
(
  cd src/bb-storage
  # Buildbarn's own build patches this generated file (patches/org_golang_google_genproto_googleapis_bytestream);
  # with plain Go, apply the same one-line change to a vendored copy.
  go mod vendor
  sed -i.bak 's/func RegisterByteStreamServer(s \*grpc.Server, srv ByteStreamServer)/func RegisterByteStreamServer(s grpc.ServiceRegistrar, srv ByteStreamServer)/' \
    vendor/google.golang.org/genproto/googleapis/bytestream/bytestream.pb.go
  go build -mod=vendor -o "$BIN/" ./cmd/bb_storage
)
(
  cd src/bb-remote-execution
  for cmd in bb_scheduler bb_worker bb_runner; do go build -o "$BIN/" "./cmd/$cmd"; done
)
ls -l "$BIN"
