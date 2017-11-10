/*
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.pravega.connectors.hadoop;

import io.pravega.client.ClientFactory;
import io.pravega.client.batch.BatchClient;
import io.pravega.client.batch.SegmentIterator;
import io.pravega.client.stream.Serializer;
import io.pravega.client.stream.impl.JavaSerializer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A RecordReader that can read events from an InputSplit(segment in pravega).
 */
@NotThreadSafe
public class PravegaInputRecordReader<V> extends RecordReader<MetadataWritable, V> {

    private static final Logger log = LoggerFactory.getLogger(PravegaInputRecordReader.class);
    private ClientFactory clientFactory;
    private BatchClient batchClient;
    private PravegaInputSplit split;
    private SegmentIterator<V> iterator;
    // Pravega Serializer to deserialize events saved in pravega
    private Serializer<V> deserializer;

    private MetadataWritable key;
    private V value;


    /**
     * Initializes RecordReader by InputSplit and TaskAttemptContext
     * <p>
     * <p>connects to pravega and prepares to read events in the InputSplit(segment)
     *
     * @param split   InputSplit
     * @param context TaskAttemptContext
     */
    @Override
    public void initialize(InputSplit split, TaskAttemptContext context) throws IOException, InterruptedException {
        initialize(split, context.getConfiguration());
    }

    public void initialize(InputSplit split, Configuration conf) throws IOException, InterruptedException {
        this.split = (PravegaInputSplit) split;
        clientFactory = ClientFactory.withScope(conf.getRaw(PravegaInputFormat.SCOPE_NAME), URI.create(conf.getRaw(PravegaInputFormat.URI_STRING)));
        batchClient = clientFactory.createBatchClient();
        // create deserializer from user input, assign default one (JavaSerializer) if none
        String deserializerClassName = conf.getRaw(PravegaInputFormat.DESERIALIZER);
        if (deserializerClassName == null) {
            deserializer = new JavaSerializer();
        } else {
            try {
                Class<?> clazz = Class.forName(deserializerClassName);
                deserializer = (Serializer<V>) clazz.newInstance();
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
                log.error("Exception when creating deserializer: {}", e);
                throw new InterruptedException(e.toString());
            }
        }
        iterator = batchClient.readSegment(this.split.getSegment(), deserializer);
    }

    /**
     * Retrieves next key value pair from InputSplit
     *
     * @return next Key/Value exists or not
     */
    @Override
    public synchronized boolean nextKeyValue() throws IOException, InterruptedException {
        if (iterator.hasNext()) {
            key = new MetadataWritable(split, iterator.getOffset());
            value = iterator.next();
            log.debug("Key: {}, Value: {} ({})", key, value, value.getClass().getName());
            return true;
        }
        return false;
    }

    @Override
    public MetadataWritable getCurrentKey() throws IOException, InterruptedException {
        return key;
    }

    @Override
    public V getCurrentValue() throws IOException, InterruptedException {
        return value;
    }

    @Override
    public synchronized float getProgress() throws IOException, InterruptedException {
        if (key != null && split.getLength() > 0) {
            return ((float) (key.getOffset() - split.getStartOffset())) / split.getLength();
        }
        return 0.0f;
    }

    @Override
    public synchronized void close() throws IOException {
        if (iterator != null) {
            iterator.close();
        }
        if (clientFactory != null) {
            clientFactory.close();
        }
    }
}
