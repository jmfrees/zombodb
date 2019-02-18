/*
 * Copyright 2017 ZomboDB, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package llc.zombodb.fast_terms;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.PrimitiveIterator;
import java.util.Set;

import org.elasticsearch.action.ShardOperationFailedException;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.StatusToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.RestActions;

import llc.zombodb.utils.CompactHashSet;
import llc.zombodb.utils.IteratorHelper;
import llc.zombodb.utils.NumberArrayLookup;

public class FastTermsResponse extends BroadcastResponse implements StatusToXContentObject {
    public enum DataType {
        NONE,
        INT,
        LONG,
        STRING
    }

    private String index;
    private DataType dataType = DataType.NONE;
    private int numShards;

    private NumberArrayLookup[] lookups = new NumberArrayLookup[0];
    private CompactHashSet<String> strings = new CompactHashSet<>();

    public FastTermsResponse() {

    }

    public FastTermsResponse(StreamInput in) throws IOException {
        readFrom(in);
    }

    FastTermsResponse(String index, int shardCount, int successfulShards, int failedShards, List<ShardOperationFailedException> shardFailures, DataType dataType) {
        super(shardCount, successfulShards, failedShards, shardFailures);
        assert dataType != null;

        this.index = index;
        this.dataType = dataType;
        this.numShards = shardCount;

        switch (dataType) {
            case INT:
            case LONG:
                lookups = new NumberArrayLookup[shardCount];
                break;
            case STRING:
                strings = new CompactHashSet<>();
                break;
        }
    }

    void addData(int shardId, Object data) {
        switch (dataType) {
            case INT:
            case LONG:
                lookups[shardId] = (NumberArrayLookup) data;
                break;
            case STRING:
                strings.addAll((CompactHashSet<String>) data);
                break;
        }
    }

    public DataType getDataType() {
        return dataType;
    }

    public int getNumShards() {
        return numShards;
    }

    public NumberArrayLookup[] getNumberLookup() {
        return lookups;
    }

    public long estimateByteSize() {
        switch (dataType) {
            case INT:
            case LONG: {
                long size = 0;
                for (NumberArrayLookup nal : lookups)
                    size += nal.estimateByteSize();
                return size;
            }

            case STRING: {
                long size = 0;
                for (String s : strings)
                    size += s.length();
                return size;
            }

            case NONE:
                return 0;

            default:
                throw new RuntimeException("Unexpected data type: " + dataType);
        }
    }

    public PrimitiveIterator.OfLong[] getNumberLookupIterators() {
        PrimitiveIterator.OfLong[] iterators = new PrimitiveIterator.OfLong[lookups.length];
        for (int i = 0; i < lookups.length; i++)
            iterators[i] = IteratorHelper.create(lookups[i].iterators());
        return iterators;
    }

    public int getDocCount() {
        if (dataType == DataType.NONE)
            return 0;

        switch (dataType) {
            case INT:
            case LONG: {
                int total = 0;
                for (NumberArrayLookup nal : lookups)
                    total += nal.size();
                return total;
            }

            case STRING: {
                return strings.size();
            }

            default:
                throw new RuntimeException("Unexpected data type: " + dataType);
        }
    }

    public CompactHashSet<String> getStrings() {
        return strings;
    }

    public String[] getSortedStrings() {
        return strings.stream().sorted().toArray(String[]::new);
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);

        index = in.readString();
        numShards = in.readVInt();
        dataType = in.readEnum(DataType.class);
        switch (dataType) {
            case INT:
            case LONG: {
                lookups = new NumberArrayLookup[numShards];
                for (int i = 0; i < numShards; i++)
                    lookups[i] = NumberArrayLookup.fromStreamInput(in);
                break;
            }

            case STRING: {
                strings = new CompactHashSet<>();
                int len = in.readVInt();
                for (int i = 0; i < len; i++)
                    strings.add(in.readString());
                break;
            }
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);

        out.writeString(index);
        out.writeVInt(numShards);
        out.writeEnum(dataType);

        switch (dataType) {
            case INT:
            case LONG: {
                for (int i = 0; i < numShards; i++) {
                    lookups[i].writeTo(out);
                }
                break;
            }

            case STRING: {
                out.writeVInt(strings.size());
                for (String s : strings)
                    out.writeString(s);
                break;
            }
        }
    }

    @Override
    public RestStatus status() {
        return RestStatus.status(getSuccessfulShards(), getTotalShards(), getShardFailures());
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        RestActions.buildBroadcastShardsHeader(builder, params, getTotalShards(), getSuccessfulShards(), 0, getFailedShards(), getShardFailures());
        builder.endObject();
        return builder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, dataType, numShards, Arrays.deepHashCode(lookups), strings);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != getClass())
            return false;

        FastTermsResponse other = (FastTermsResponse) obj;
        return Objects.equals(index, other.index) &&
                Objects.equals(dataType, other.dataType) &&
                Objects.equals(numShards, other.numShards) &&
                Objects.deepEquals(lookups, other.lookups) &&
                Objects.equals(strings, other.strings);
    }

    public FastTermsResponse throwShardFailure() {
        if (getFailedShards() > 0) {
            // if there was at least one failure, report and re-throw the first
            // Note that even after we walk down to the original cause, the stacktrace is already lost.
            Throwable cause = getShardFailures()[0].getCause();
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw new RuntimeException(cause);
        }

        return this;
    }

}
