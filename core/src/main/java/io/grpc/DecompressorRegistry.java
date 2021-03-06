/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.internal.GrpcUtil.ACCEPT_ENCODING_JOINER;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Encloses classes related to the compression and decompression of messages.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
@ThreadSafe
public final class DecompressorRegistry {
  public static DecompressorRegistry emptyInstance() {
    return new DecompressorRegistry();
  }

  private static final DecompressorRegistry DEFAULT_INSTANCE =
      emptyInstance()
      .with(new Codec.Gzip(), true)
      .with(Codec.Identity.NONE, false);

  public static DecompressorRegistry getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private final Map<String, DecompressorInfo> decompressors;
  private final String advertisedDecompressors;

  /**
   * Registers a decompressor for both decompression and message encoding negotiation.  Returns a
   * new registry.
   *
   * @param d The decompressor to register
   * @param advertised If true, the message encoding will be listed in the Accept-Encoding header.
   */
  public DecompressorRegistry with(Decompressor d, boolean advertised) {
    return new DecompressorRegistry(d, advertised, this);
  }

  private DecompressorRegistry(Decompressor d, boolean advertised, DecompressorRegistry parent) {
    String encoding = d.getMessageEncoding();
    checkArgument(!encoding.contains(","), "Comma is currently not allowed in message encoding");

    int newSize = parent.decompressors.size();
    if (!parent.decompressors.containsKey(d.getMessageEncoding())) {
      newSize++;
    }
    Map<String, DecompressorInfo> newDecompressors =
        new LinkedHashMap<String, DecompressorInfo>(newSize);
    for (DecompressorInfo di : parent.decompressors.values()) {
      String previousEncoding = di.decompressor.getMessageEncoding();
      if (!previousEncoding.equals(encoding)) {
        newDecompressors.put(
            previousEncoding, new DecompressorInfo(di.decompressor, di.advertised));
      }
    }
    newDecompressors.put(encoding, new DecompressorInfo(d, advertised));

    decompressors = Collections.unmodifiableMap(newDecompressors);
    advertisedDecompressors = ACCEPT_ENCODING_JOINER.join(getAdvertisedMessageEncodings());
  }

  private DecompressorRegistry() {
    decompressors = new LinkedHashMap<String, DecompressorInfo>(0);
    advertisedDecompressors = "";
  }

  /**
   * Provides a list of all message encodings that have decompressors available.
   */
  public Set<String> getKnownMessageEncodings() {
    return decompressors.keySet();
  }

  public String getRawAdvertisedMessageEncodings() {
    return advertisedDecompressors;
  }

  /**
   * Provides a list of all message encodings that have decompressors available and should be
   * advertised.
   *
   * <p>The specification doesn't say anything about ordering, or preference, so the returned codes
   * can be arbitrary.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
  public Set<String> getAdvertisedMessageEncodings() {
    Set<String> advertisedDecompressors = new HashSet<String>(decompressors.size());
    for (Entry<String, DecompressorInfo> entry : decompressors.entrySet()) {
      if (entry.getValue().advertised) {
        advertisedDecompressors.add(entry.getKey());
      }
    }
    return Collections.unmodifiableSet(advertisedDecompressors);
  }

  /**
   * Returns a decompressor for the given message encoding, or {@code null} if none has been
   * registered.
   *
   * <p>This ignores whether the compressor is advertised.  According to the spec, if we know how
   * to process this encoding, we attempt to, regardless of whether or not it is part of the
   * encodings sent to the remote host.
   */
  @Nullable
  public Decompressor lookupDecompressor(String messageEncoding) {
    DecompressorInfo info = decompressors.get(messageEncoding);
    return info != null ? info.decompressor : null;
  }

  /**
   * Information about a decompressor.
   */
  private static final class DecompressorInfo {
    final Decompressor decompressor;
    final boolean advertised;

    DecompressorInfo(Decompressor decompressor, boolean advertised) {
      this.decompressor = checkNotNull(decompressor);
      this.advertised = advertised;
    }
  }
}
