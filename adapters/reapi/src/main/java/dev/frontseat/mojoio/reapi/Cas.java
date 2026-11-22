package dev.frontseat.mojoio.reapi;

import build.bazel.remote.execution.v2.BatchReadBlobsRequest;
import build.bazel.remote.execution.v2.BatchReadBlobsResponse;
import build.bazel.remote.execution.v2.BatchUpdateBlobsRequest;
import build.bazel.remote.execution.v2.BatchUpdateBlobsResponse;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamProto;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** The Content Addressable Storage of a REAPI endpoint: upload what is missing, read blobs back. */
final class Cas {

    /** Blobs up to this size go through the batch calls; larger ones through ByteStream. */
    private static final int BATCH_LIMIT = 1024 * 1024;
    private static final int CHUNK = 256 * 1024;

    private final String instance;
    private final ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub cas;
    private final ByteStreamGrpc.ByteStreamStub byteStreamWrite;
    private final ByteStreamGrpc.ByteStreamBlockingStub byteStreamRead;

    Cas(Channel channel, String instance) {
        this.instance = instance;
        this.cas = ContentAddressableStorageGrpc.newBlockingStub(channel);
        this.byteStreamWrite = ByteStreamGrpc.newStub(channel);
        this.byteStreamRead = ByteStreamGrpc.newBlockingStub(channel);
    }

    static Digest digest(byte[] content) {
        try {
            return Digest.newBuilder()
                    .setHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)))
                    .setSizeBytes(content.length).build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Digest digest(Path file) {
        try {
            return digest(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Uploads the blobs the server does not have yet. Returns how many were uploaded. */
    int upload(Map<Digest, Supplier<byte[]>> blobs) {
        if (blobs.isEmpty()) {
            return 0;
        }
        List<Digest> missing = new ArrayList<>();
        List<Digest> all = new ArrayList<>(blobs.keySet());
        for (int i = 0; i < all.size(); i += 2000) {
            missing.addAll(cas.findMissingBlobs(FindMissingBlobsRequest.newBuilder().setInstanceName(instance)
                    .addAllBlobDigests(all.subList(i, Math.min(all.size(), i + 2000))).build()).getMissingBlobDigestsList());
        }
        BatchUpdateBlobsRequest.Builder batch = BatchUpdateBlobsRequest.newBuilder().setInstanceName(instance);
        long batchBytes = 0;
        for (Digest d : missing) {
            byte[] content = blobs.get(d).get();
            if (content.length > BATCH_LIMIT) {
                write(d, content);
                continue;
            }
            if (batchBytes + content.length > BATCH_LIMIT) {
                flush(batch);
                batch = BatchUpdateBlobsRequest.newBuilder().setInstanceName(instance);
                batchBytes = 0;
            }
            batch.addRequests(BatchUpdateBlobsRequest.Request.newBuilder().setDigest(d).setData(ByteString.copyFrom(content)));
            batchBytes += content.length + 100;
        }
        flush(batch);
        return missing.size();
    }

    private void flush(BatchUpdateBlobsRequest.Builder batch) {
        if (batch.getRequestsCount() == 0) {
            return;
        }
        BatchUpdateBlobsResponse response = cas.batchUpdateBlobs(batch.build());
        for (BatchUpdateBlobsResponse.Response r : response.getResponsesList()) {
            if (r.getStatus().getCode() != 0) {
                throw new IllegalStateException("upload of " + r.getDigest().getHash() + " failed: " + r.getStatus().getMessage());
            }
        }
    }

    private void write(Digest d, byte[] content) {
        String resource = (instance.isEmpty() ? "" : instance + "/") + "uploads/" + UUID.randomUUID() + "/blobs/"
                + d.getHash() + "/" + d.getSizeBytes();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StreamObserver<ByteStreamProto.WriteRequest> out = byteStreamWrite.write(new StreamObserver<>() {
            @Override
            public void onNext(ByteStreamProto.WriteResponse value) { }

            @Override
            public void onError(Throwable t) {
                failure.set(t);
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        });
        for (int offset = 0; offset < content.length; offset += CHUNK) {
            int end = Math.min(content.length, offset + CHUNK);
            ByteStreamProto.WriteRequest.Builder r = ByteStreamProto.WriteRequest.newBuilder().setWriteOffset(offset)
                    .setData(ByteString.copyFrom(content, offset, end - offset)).setFinishWrite(end == content.length);
            if (offset == 0) {
                r.setResourceName(resource);
            }
            out.onNext(r.build());
        }
        out.onCompleted();
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("upload of " + d.getHash() + " failed", failure.get());
        }
    }

    byte[] read(Digest d) {
        if (d.getSizeBytes() == 0) {
            return new byte[0];
        }
        if (d.getSizeBytes() <= BATCH_LIMIT) {
            return readAll(List.of(d)).get(d);
        }
        String resource = (instance.isEmpty() ? "" : instance + "/") + "blobs/" + d.getHash() + "/" + d.getSizeBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) d.getSizeBytes());
        Iterator<ByteStreamProto.ReadResponse> it = byteStreamRead.read(
                ByteStreamProto.ReadRequest.newBuilder().setResourceName(resource).build());
        while (it.hasNext()) {
            out.writeBytes(it.next().getData().toByteArray());
        }
        return out.toByteArray();
    }

    /** Reads small blobs in batches; large ones one by one. */
    Map<Digest, byte[]> readAll(List<Digest> digests) {
        Map<Digest, byte[]> result = new LinkedHashMap<>();
        BatchReadBlobsRequest.Builder batch = BatchReadBlobsRequest.newBuilder().setInstanceName(instance);
        long batchBytes = 0;
        for (Digest d : new java.util.LinkedHashSet<>(digests)) {
            if (d.getSizeBytes() == 0) {
                result.put(d, new byte[0]);
            } else if (d.getSizeBytes() > BATCH_LIMIT) {
                result.put(d, read(d));
            } else {
                if (batchBytes + d.getSizeBytes() > BATCH_LIMIT) {
                    readBatch(batch, result);
                    batch = BatchReadBlobsRequest.newBuilder().setInstanceName(instance);
                    batchBytes = 0;
                }
                batch.addDigests(d);
                batchBytes += d.getSizeBytes() + 100;
            }
        }
        readBatch(batch, result);
        return result;
    }

    private void readBatch(BatchReadBlobsRequest.Builder batch, Map<Digest, byte[]> result) {
        if (batch.getDigestsCount() == 0) {
            return;
        }
        BatchReadBlobsResponse response = cas.batchReadBlobs(batch.build());
        for (BatchReadBlobsResponse.Response r : response.getResponsesList()) {
            if (r.getStatus().getCode() != 0) {
                throw new IllegalStateException("read of " + r.getDigest().getHash() + " failed: " + r.getStatus().getMessage());
            }
            result.put(r.getDigest(), r.getData().toByteArray());
        }
    }
}
