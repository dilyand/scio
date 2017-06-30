/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.bigtable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.bigtable.v2.MutateRowResponse;
import com.google.bigtable.v2.Mutation;
import com.google.bigtable.v2.Row;
import com.google.bigtable.v2.RowFilter;
import com.google.bigtable.v2.SampleRowKeysResponse;
import com.google.cloud.bigtable.config.BigtableOptions;
import com.google.cloud.bigtable.config.CredentialOptions;
import com.google.cloud.bigtable.config.CredentialOptions.CredentialType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.protobuf.ProtoCoder;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.io.BoundedSource.BoundedReader;
import org.apache.beam.sdk.io.gcp.bigtable.BigtableIO.BigtableSource;
import org.apache.beam.sdk.io.range.ByteKey;
import org.apache.beam.sdk.io.range.ByteKeyRange;
import org.apache.beam.sdk.io.range.ByteKeyRangeTracker;
import org.apache.beam.sdk.options.GcpOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.runners.PipelineRunner;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.util.ReleaseInfo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A bounded source and sink for Google Cloud Bigtable.
 *
 * <p>For more information, see the online documentation at
 * <a href="https://cloud.google.com/bigtable/">Google Cloud Bigtable</a>.
 *
 * <h3>Reading from Cloud Bigtable</h3>
 *
 * <p>The Bigtable source returns a set of rows from a single table, returning a
 * {@code PCollection<Row>}.
 *
 * <p>To configure a Cloud Bigtable source, you must supply a table id and a {@link BigtableOptions}
 * or builder configured with the project and other information necessary to identify the
 * Bigtable instance. By default, {@link PatchedBigtableIO.Read} will read all rows in the table. The row
 * range to be read can optionally be restricted using {@link PatchedBigtableIO.Read#withKeyRange}, and
 * a {@link RowFilter} can be specified using {@link PatchedBigtableIO.Read#withRowFilter}. For example:
 *
 * <pre>{@code
 * BigtableOptions.Builder optionsBuilder =
 *     new BigtableOptions.Builder()
 *         .setProjectId("project")
 *         .setInstanceId("instance");
 *
 * Pipeline p = ...;
 *
 * // Scan the entire table.
 * p.apply("read",
 *     PatchedBigtableIO.read()
 *         .withBigtableOptions(optionsBuilder)
 *         .withTableId("table"));
 *
 * // Scan a prefix of the table.
 * ByteKeyRange keyRange = ...;
 * p.apply("read",
 *     PatchedBigtableIO.read()
 *         .withBigtableOptions(optionsBuilder)
 *         .withTableId("table")
 *         .withKeyRange(keyRange));
 *
 * // Scan a subset of rows that match the specified row filter.
 * p.apply("filtered read",
 *     PatchedBigtableIO.read()
 *         .withBigtableOptions(optionsBuilder)
 *         .withTableId("table")
 *         .withRowFilter(filter));
 * }</pre>
 *
 * <h3>Writing to Cloud Bigtable</h3>
 *
 * <p>The Bigtable sink executes a set of row mutations on a single table. It takes as input a
 * {@link PCollection PCollection&lt;KV&lt;ByteString, Iterable&lt;Mutation&gt;&gt;&gt;}, where the
 * {@link ByteString} is the key of the row being mutated, and each {@link Mutation} represents an
 * idempotent transformation to that row.
 *
 * <p>To configure a Cloud Bigtable sink, you must supply a table id and a {@link BigtableOptions}
 * or builder configured with the project and other information necessary to identify the
 * Bigtable instance, for example:
 *
 * <pre>{@code
 * BigtableOptions.Builder optionsBuilder =
 *     new BigtableOptions.Builder()
 *         .setProjectId("project")
 *         .setInstanceId("instance");
 *
 * PCollection<KV<ByteString, Iterable<Mutation>>> data = ...;
 *
 * data.apply("write",
 *     PatchedBigtableIO.write()
 *         .withBigtableOptions(optionsBuilder)
 *         .withTableId("table"));
 * }</pre>
 *
 * <h3>Experimental</h3>
 *
 * <p>This connector for Cloud Bigtable is considered experimental and may break or receive
 * backwards-incompatible changes in future versions of the Cloud Dataflow SDK. Cloud Bigtable is
 * in Beta, and thus it may introduce breaking changes in future revisions of its service or APIs.
 *
 * <h3>Permissions</h3>
 *
 * <p>Permission requirements depend on the {@link PipelineRunner} that is used to execute the
 * Dataflow job. Please refer to the documentation of corresponding
 * {@link PipelineRunner PipelineRunners} for more details.
 */
@Experimental
public class PatchedBigtableIO {
  private static final Logger LOG = LoggerFactory.getLogger(PatchedBigtableIO.class);

  /**
   * Creates an uninitialized {@link PatchedBigtableIO.Read}. Before use, the {@code Read} must be
   * initialized with a
   * {@link PatchedBigtableIO.Read#withBigtableOptions(BigtableOptions) BigtableOptions} that specifies
   * the source Cloud Bigtable instance, and a {@link PatchedBigtableIO.Read#withTableId tableId} that
   * specifies which table to read. A {@link RowFilter} may also optionally be specified using
   * {@link PatchedBigtableIO.Read#withRowFilter}.
   */
  @Experimental
  public static Read read() {
    return new Read(null, "", ByteKeyRange.ALL_KEYS, null, null);
  }

  /**
   * Creates an uninitialized {@link PatchedBigtableIO.Write}. Before use, the {@code Write} must be
   * initialized with a
   * {@link PatchedBigtableIO.Write#withBigtableOptions(BigtableOptions) BigtableOptions} that specifies
   * the destination Cloud Bigtable instance, and a {@link PatchedBigtableIO.Write#withTableId tableId}
   * that specifies which table to write.
   */
  @Experimental
  public static Write write() {
    return new Write(null, "", null);
  }

  /**
   * A {@link PTransform} that reads from Google Cloud Bigtable. See the class-level Javadoc on
   * {@link PatchedBigtableIO} for more information.
   *
   * @see PatchedBigtableIO
   */
  @Experimental
  public static class Read extends PTransform<PBegin, PCollection<Row>> {
    /**
     * Returns a new {@link PatchedBigtableIO.Read} that will read from the Cloud Bigtable instance
     * indicated by the given options, and using any other specified customizations.
     *
     * <p>Does not modify this object.
     */
    public Read withBigtableOptions(BigtableOptions options) {
      checkNotNull(options, "options");
      return withBigtableOptions(options.toBuilder());
    }

    /**
     * Returns a new {@link PatchedBigtableIO.Read} that will read from the Cloud Bigtable instance
     * indicated by the given options, and using any other specified customizations.
     *
     * <p>Clones the given {@link BigtableOptions} builder so that any further changes
     * will have no effect on the returned {@link PatchedBigtableIO.Read}.
     *
     * <p>Does not modify this object.
     */
    public Read withBigtableOptions(BigtableOptions.Builder optionsBuilder) {
      checkNotNull(optionsBuilder, "optionsBuilder");
      // TODO: is there a better way to clone a Builder? Want it to be immune from user changes.
      BigtableOptions options = optionsBuilder.build();

      // Set data channel count to one because there is only 1 scanner in this session
      BigtableOptions.Builder clonedBuilder = options.toBuilder()
          .setDataChannelCount(1);
      BigtableOptions optionsWithAgent = clonedBuilder.setUserAgent(getUserAgent()).build();

      return new Read(optionsWithAgent, tableId, keyRange, filter, bigtableService);
    }

    /**
     * Returns a new {@link PatchedBigtableIO.Read} that will filter the rows read from Cloud Bigtable
     * using the given row filter.
     *
     * <p>Does not modify this object.
     */
    public Read withRowFilter(RowFilter filter) {
      checkNotNull(filter, "filter");
      return new Read(options, tableId, keyRange, filter, bigtableService);
    }

    /**
     * Returns a new {@link PatchedBigtableIO.Read} that will read only rows in the specified range.
     *
     * <p>Does not modify this object.
     */
    public Read withKeyRange(ByteKeyRange keyRange) {
      checkNotNull(keyRange, "keyRange");
      return new Read(options, tableId, keyRange, filter, bigtableService);
    }

    /**
     * Returns a new {@link PatchedBigtableIO.Read} that will read from the specified table.
     *
     * <p>Does not modify this object.
     */
    public Read withTableId(String tableId) {
      checkNotNull(tableId, "tableId");
      return new Read(options, tableId, keyRange, filter, bigtableService);
    }

    /**
     * Returns the Google Cloud Bigtable instance being read from, and other parameters.
     */
    public BigtableOptions getBigtableOptions() {
      return options;
    }

    /**
     * Returns the range of keys that will be read from the table. By default, returns
     * {@link ByteKeyRange#ALL_KEYS} to scan the entire table.
     */
    public ByteKeyRange getKeyRange() {
      return keyRange;
    }

    /**
     * Returns the table being read from.
     */
    public String getTableId() {
      return tableId;
    }

    @Override
    public PCollection<Row> expand(PBegin input) {
      BigtableSource source =
          new BigtableSource(new SerializableFunction<PipelineOptions, BigtableService>() {
            @Override
            public BigtableService apply(PipelineOptions options) {
              return getBigtableService(options);
            }
          }, tableId, filter, keyRange, null);
      return input.getPipeline().apply(org.apache.beam.sdk.io.Read.from(source));
    }

    @Override
    public void validate(PBegin input) {
      checkArgument(options != null, "BigtableOptions not specified");
      checkArgument(!tableId.isEmpty(), "Table ID not specified");
      try {
        checkArgument(
            getBigtableService(input.getPipeline().getOptions()).tableExists(tableId),
            "Table %s does not exist",
            tableId);
      } catch (IOException e) {
        LOG.warn("Error checking whether table {} exists; proceeding.", tableId, e);
      }
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);

      builder.add(DisplayData.item("tableId", tableId)
        .withLabel("Table ID"));

      if (options != null) {
        builder.add(DisplayData.item("bigtableOptions", options.toString())
          .withLabel("Bigtable Options"));
      }

      builder.addIfNotDefault(
          DisplayData.item("keyRange", keyRange.toString()), ByteKeyRange.ALL_KEYS.toString());

      if (filter != null) {
        builder.add(DisplayData.item("rowFilter", filter.toString())
          .withLabel("Table Row Filter"));
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(Read.class)
          .add("options", options)
          .add("tableId", tableId)
          .add("keyRange", keyRange)
          .add("filter", filter)
          .toString();
    }

    /////////////////////////////////////////////////////////////////////////////////////////
    /**
     * Used to define the Cloud Bigtable instance and any options for the networking layer.
     * Cannot actually be {@code null} at validation time, but may start out {@code null} while
     * source is being built.
     */
    @Nullable private final BigtableOptions options;
    private final String tableId;
    private final ByteKeyRange keyRange;
    @Nullable private final RowFilter filter;
    @Nullable private final BigtableService bigtableService;

    private Read(
        @Nullable BigtableOptions options,
        String tableId,
        ByteKeyRange keyRange,
        @Nullable RowFilter filter,
        @Nullable BigtableService bigtableService) {
      this.options = options;
      this.tableId = checkNotNull(tableId, "tableId");
      this.keyRange = checkNotNull(keyRange, "keyRange");
      this.filter = filter;
      this.bigtableService = bigtableService;
    }

    /**
     * Returns a new {@link PatchedBigtableIO.Read} that will read using the given Cloud Bigtable
     * service implementation.
     *
     * <p>This is used for testing.
     *
     * <p>Does not modify this object.
     */
    Read withBigtableService(BigtableService bigtableService) {
      checkNotNull(bigtableService, "bigtableService");
      return new Read(options, tableId, keyRange, filter, bigtableService);
    }

    /**
     * Helper function that either returns the mock Bigtable service supplied by
     * {@link #withBigtableService} or creates and returns an implementation that talks to
     * {@code Cloud Bigtable}.
     *
     * <p>Also populate the credentials option from {@link GcpOptions#getGcpCredential()} if the
     * default credentials are being used on {@link BigtableOptions}.
     */
    @VisibleForTesting
    BigtableService getBigtableService(PipelineOptions pipelineOptions) {
      if (bigtableService != null) {
        return bigtableService;
      }
      BigtableOptions.Builder clonedOptions = options.toBuilder();
      if (options.getCredentialOptions().getCredentialType() == CredentialType.DefaultCredentials) {
        clonedOptions.setCredentialOptions(
            CredentialOptions.credential(
                pipelineOptions.as(GcpOptions.class).getGcpCredential()));
      }
      return new BigtableServiceImpl(clonedOptions.build());
    }
  }

  /**
   * A {@link PTransform} that writes to Google Cloud Bigtable. See the class-level Javadoc on
   * {@link PatchedBigtableIO} for more information.
   *
   * @see PatchedBigtableIO
   */
  @Experimental
  public static class Write
      extends PTransform<PCollection<KV<ByteString, Iterable<Mutation>>>, PDone> {
    /**
     * Used to define the Cloud Bigtable instance and any options for the networking layer.
     * Cannot actually be {@code null} at validation time, but may start out {@code null} while
     * source is being built.
     */
    @Nullable private final BigtableOptions options;
    private final String tableId;
    @Nullable private final BigtableService bigtableService;

    private Write(
        @Nullable BigtableOptions options,
        String tableId,
        @Nullable BigtableService bigtableService) {
      this.options = options;
      this.tableId = checkNotNull(tableId, "tableId");
      this.bigtableService = bigtableService;
    }

    /**
     * Returns a new {@link PatchedBigtableIO.Write} that will write to the Cloud Bigtable instance
     * indicated by the given options, and using any other specified customizations.
     *
     * <p>Does not modify this object.
     */
    public Write withBigtableOptions(BigtableOptions options) {
      checkNotNull(options, "options");
      return withBigtableOptions(options.toBuilder());
    }

    /**
     * Returns a new {@link PatchedBigtableIO.Write} that will write to the Cloud Bigtable instance
     * indicated by the given options, and using any other specified customizations.
     *
     * <p>Clones the given {@link BigtableOptions} builder so that any further changes
     * will have no effect on the returned {@link PatchedBigtableIO.Write}.
     *
     * <p>Does not modify this object.
     */
    public Write withBigtableOptions(BigtableOptions.Builder optionsBuilder) {
      checkNotNull(optionsBuilder, "optionsBuilder");
      // TODO: is there a better way to clone a Builder? Want it to be immune from user changes.
      BigtableOptions options = optionsBuilder.build();

      // Set useBulkApi to true for enabling bulk writes
      BigtableOptions.Builder clonedBuilder = options.toBuilder()
          .setBulkOptions(
              options.getBulkOptions().toBuilder()
                  .setUseBulkApi(true)
                  .build());
      BigtableOptions optionsWithAgent = clonedBuilder.setUserAgent(getUserAgent()).build();
      return new Write(optionsWithAgent, tableId, bigtableService);
    }

    /**
     * Returns a new {@link PatchedBigtableIO.Write} that will write to the specified table.
     *
     * <p>Does not modify this object.
     */
    public Write withTableId(String tableId) {
      checkNotNull(tableId, "tableId");
      return new Write(options, tableId, bigtableService);
    }

    /**
     * Returns the Google Cloud Bigtable instance being written to, and other parameters.
     */
    public BigtableOptions getBigtableOptions() {
      return options;
    }

    /**
     * Returns the table being written to.
     */
    public String getTableId() {
      return tableId;
    }

    @Override
    public PDone expand(PCollection<KV<ByteString, Iterable<Mutation>>> input) {
      input.apply(ParDo.of(new BigtableWriterFn(tableId,
          new SerializableFunction<PipelineOptions, BigtableService>() {
        @Override
        public BigtableService apply(PipelineOptions options) {
          return getBigtableService(options);
        }
      })));
      return PDone.in(input.getPipeline());
    }

    @Override
    public void validate(PCollection<KV<ByteString, Iterable<Mutation>>> input) {
      checkArgument(options != null, "BigtableOptions not specified");
      checkArgument(!tableId.isEmpty(), "Table ID not specified");
      try {
        checkArgument(
            getBigtableService(input.getPipeline().getOptions()).tableExists(tableId),
            "Table %s does not exist",
            tableId);
      } catch (IOException e) {
        LOG.warn("Error checking whether table {} exists; proceeding.", tableId, e);
      }
    }

    /**
     * Returns a new {@link PatchedBigtableIO.Write} that will write using the given Cloud Bigtable
     * service implementation.
     *
     * <p>This is used for testing.
     *
     * <p>Does not modify this object.
     */
    Write withBigtableService(BigtableService bigtableService) {
      checkNotNull(bigtableService, "bigtableService");
      return new Write(options, tableId, bigtableService);
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);

      builder.add(DisplayData.item("tableId", tableId)
        .withLabel("Table ID"));

      if (options != null) {
        builder.add(DisplayData.item("bigtableOptions", options.toString())
          .withLabel("Bigtable Options"));
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(Write.class)
          .add("options", options)
          .add("tableId", tableId)
          .toString();
    }

    /**
     * Helper function that either returns the mock Bigtable service supplied by
     * {@link #withBigtableService} or creates and returns an implementation that talks to
     * {@code Cloud Bigtable}.
     *
     * <p>Also populate the credentials option from {@link GcpOptions#getGcpCredential()} if the
     * default credentials are being used on {@link BigtableOptions}.
     */
    @VisibleForTesting
    BigtableService getBigtableService(PipelineOptions pipelineOptions) {
      if (bigtableService != null) {
        return bigtableService;
      }
      BigtableOptions.Builder clonedOptions = options.toBuilder();
      if (options.getCredentialOptions().getCredentialType() == CredentialType.DefaultCredentials) {
        clonedOptions.setCredentialOptions(
            CredentialOptions.credential(
                pipelineOptions.as(GcpOptions.class).getGcpCredential()));
      }
      return new BigtableServiceImpl(clonedOptions.build());
    }

    private class BigtableWriterFn extends DoFn<KV<ByteString, Iterable<Mutation>>, Void> {

      public BigtableWriterFn(String tableId,
          SerializableFunction<PipelineOptions, BigtableService> bigtableServiceFactory) {
        this.tableId = checkNotNull(tableId, "tableId");
        this.bigtableServiceFactory =
            checkNotNull(bigtableServiceFactory, "bigtableServiceFactory");
        this.failures = new ConcurrentLinkedQueue<>();
      }

      @StartBundle
      public void startBundle(Context c) throws IOException {
        if (bigtableWriter == null) {
          bigtableWriter = bigtableServiceFactory.apply(
              c.getPipelineOptions()).openForWriting(tableId);
        }
        recordsWritten = 0;
      }

      @ProcessElement
      public void processElement(ProcessContext c) throws Exception {
        checkForFailures();
        Futures.addCallback(
            bigtableWriter.writeRecord(c.element()), new WriteExceptionCallback(c.element()));
        ++recordsWritten;
      }

      @FinishBundle
      public void finishBundle(Context c) throws Exception {
        bigtableWriter.flush();
        checkForFailures();
        LOG.info("Wrote {} records", recordsWritten);
      }

      @Teardown
      public void tearDown() throws Exception {
        bigtableWriter.close();
        bigtableWriter = null;
      }

      @Override
      public void populateDisplayData(DisplayData.Builder builder) {
        builder.delegate(Write.this);
      }

      ///////////////////////////////////////////////////////////////////////////////
      private final String tableId;
      private final SerializableFunction<PipelineOptions, BigtableService> bigtableServiceFactory;
      private BigtableService.Writer bigtableWriter;
      private long recordsWritten;
      private final ConcurrentLinkedQueue<BigtableWriteException> failures;

      /**
       * If any write has asynchronously failed, fail the bundle with a useful error.
       */
      private void checkForFailures() throws IOException {
        // Note that this function is never called by multiple threads and is the only place that
        // we remove from failures, so this code is safe.
        if (failures.isEmpty()) {
          return;
        }

        IOException failure = new IOException(
            String.format(
                "At least %d errors occurred writing to Bigtable. Some added to suppressed list.",
                failures.size()));

        StringBuilder logEntry = new StringBuilder();
        int i = 0;
        for (; i < 10 && !failures.isEmpty(); ++i) {
          BigtableWriteException exc = failures.remove();
          logEntry.append("\n").append(exc.getMessage());
          if (exc.getCause() != null) {
            logEntry.append(": ").append(exc.getCause().getMessage());
          }
          failure.addSuppressed(exc);
        }
        String message =
            String.format(
                "At least %d errors occurred writing to Bigtable. First %d errors: %s",
                i + failures.size(),
                i,
                logEntry.toString());
        LOG.error(message);
        throw failure;
      }

      private class WriteExceptionCallback implements FutureCallback<MutateRowResponse> {
        private final KV<ByteString, Iterable<Mutation>> value;

        public WriteExceptionCallback(KV<ByteString, Iterable<Mutation>> value) {
          this.value = value;
        }

        @Override
        public void onFailure(Throwable cause) {
          failures.add(new BigtableWriteException(value, cause));
        }

        @Override
        public void onSuccess(MutateRowResponse produced) {}
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////
  /** Disallow construction of utility class. */
  private PatchedBigtableIO() {}

  private static class BigtableReader extends BoundedReader<Row> {
    // Thread-safety: source is protected via synchronization and is only accessed or modified
    // inside a synchronized block (or constructor, which is the same).
    private BigtableSource source;
    private BigtableService service;
    private BigtableService.Reader reader;
    private final ByteKeyRangeTracker rangeTracker;
    private long recordsReturned;

    public BigtableReader(BigtableSource source, BigtableService service) {
      this.source = source;
      this.service = service;
      rangeTracker = ByteKeyRangeTracker.of(source.getRange());
    }

    @Override
    public boolean start() throws IOException {
      reader = service.createReader(getCurrentSource());
      boolean hasRecord =
          reader.start()
              && rangeTracker.tryReturnRecordAt(true, ByteKey.of(reader.getCurrentRow().getKey()))
              || rangeTracker.markDone();
      if (hasRecord) {
        ++recordsReturned;
      }
      return hasRecord;
    }

    @Override
    public synchronized BigtableSource getCurrentSource() {
      return source;
    }

    @Override
    public boolean advance() throws IOException {
      boolean hasRecord =
          reader.advance()
              && rangeTracker.tryReturnRecordAt(true, ByteKey.of(reader.getCurrentRow().getKey()))
              || rangeTracker.markDone();
      if (hasRecord) {
        ++recordsReturned;
      }
      return hasRecord;
    }

    @Override
    public Row getCurrent() throws NoSuchElementException {
      return reader.getCurrentRow();
    }

    @Override
    public void close() throws IOException {
      LOG.info("Closing reader after reading {} records.", recordsReturned);
      if (reader != null) {
        reader.close();
        reader = null;
      }
    }

    @Override
    public final Double getFractionConsumed() {
      return rangeTracker.getFractionConsumed();
    }

    @Override
    public final long getSplitPointsConsumed() {
      return rangeTracker.getSplitPointsConsumed();
    }

    @Override
    public final synchronized BigtableSource splitAtFraction(double fraction) {
      ByteKey splitKey;
      try {
        splitKey = rangeTracker.getRange().interpolateKey(fraction);
      } catch (IllegalArgumentException e) {
        LOG.info(
            "%s: Failed to interpolate key for fraction %s.", rangeTracker.getRange(), fraction);
        return null;
      }
      LOG.debug(
          "Proposing to split {} at fraction {} (key {})", rangeTracker, fraction, splitKey);
      BigtableSource primary = source.withEndKey(splitKey);
      BigtableSource residual = source.withStartKey(splitKey);
      if (!rangeTracker.trySplitAtPosition(splitKey)) {
        return null;
      }
      this.source = primary;
      return residual;
    }
  }

  /**
   * An exception that puts information about the failed record being written in its message.
   */
  static class BigtableWriteException extends IOException {
    public BigtableWriteException(KV<ByteString, Iterable<Mutation>> record, Throwable cause) {
      super(
          String.format(
              "Error mutating row %s with mutations %s",
              record.getKey().toStringUtf8(),
              record.getValue()),
          cause);
    }
  }

  /**
   * A helper function to produce a Cloud Bigtable user agent string.
   */
  private static String getUserAgent() {
    String javaVersion = System.getProperty("java.specification.version");
    ReleaseInfo info = ReleaseInfo.getReleaseInfo();
    return String.format(
        "%s/%s (%s); %s",
        info.getName(),
        info.getVersion(),
        javaVersion,
        "0.3.0" /* TODO get Bigtable client version directly from jar. */);
  }
}