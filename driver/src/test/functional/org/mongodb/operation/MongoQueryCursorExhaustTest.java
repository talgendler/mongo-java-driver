/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.operation;

import org.bson.ByteBuf;
import org.junit.Before;
import org.junit.Test;
import org.mongodb.DatabaseTestCase;
import org.mongodb.Document;
import org.mongodb.MongoFuture;
import org.mongodb.ReadPreference;
import org.mongodb.connection.Connection;
import org.mongodb.connection.ResponseBuffers;
import org.mongodb.connection.Server;
import org.mongodb.connection.ServerAddress;
import org.mongodb.connection.ServerDescription;
import org.mongodb.connection.SingleResultCallback;
import org.mongodb.selector.PrimaryServerSelector;
import org.mongodb.selector.ReadPreferenceServerSelector;
import org.mongodb.session.ServerConnectionProvider;
import org.mongodb.session.ServerConnectionProviderOptions;
import org.mongodb.session.Session;

import java.util.EnumSet;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;
import static org.mongodb.Fixture.getCluster;
import static org.mongodb.Fixture.getSession;
import static org.mongodb.Fixture.isSharded;
import static org.mongodb.assertions.Assertions.isTrue;
import static org.mongodb.assertions.Assertions.notNull;
import static org.mongodb.operation.QueryFlag.Exhaust;

public class MongoQueryCursorExhaustTest extends DatabaseTestCase {

    private final byte[] bytes = new byte[10000];

    @Before
    public void setUp() {
        super.setUp();

        for (int i = 0; i < 1000; i++) {
            collection.insert(new Document("_id", i).append("bytes", bytes));
        }
    }

    @Test
    public void testExhaustReadAllDocuments() {
        assumeFalse(isSharded());
        MongoQueryCursor<Document> cursor = new MongoQueryCursor<Document>(collection.getNamespace(),
                                                                           new Find().addFlags(EnumSet.of(Exhaust)),
                                                                           collection.getOptions().getDocumentCodec(),
                                                                           collection.getCodec(),
                                                                           getConnectionProvider(getSession())
        );

        int count = 0;
        while (cursor.hasNext()) {
            cursor.next();
            count++;
        }
        assertEquals(1000, count);
    }

    @Test
    public void testExhaustCloseBeforeReadingAllDocuments() {
        assumeFalse(isSharded());
        Server server = getCluster().selectServer(new ReadPreferenceServerSelector(ReadPreference.primary()), 1, SECONDS);
        Connection connection = server.getConnection();
        try {
            SingleConnectionSession singleConnectionSession = new SingleConnectionSession(server.getDescription(), connection);

            MongoQueryCursor<Document> cursor = new MongoQueryCursor<Document>(collection.getNamespace(),
                                                                               new Find().addFlags(EnumSet.of(Exhaust)),
                                                                               collection.getOptions().getDocumentCodec(),
                                                                               collection.getCodec(),
                                                                               getConnectionProvider(singleConnectionSession));

            cursor.next();
            cursor.close();

            cursor = new MongoQueryCursor<Document>(collection.getNamespace(),
                                                    new Find().limit(1).select(new Document("_id", 1)).order(new Document("_id", -1)),
                                                    collection.getOptions().getDocumentCodec(),
                                                    collection.getCodec(),
                                                    getConnectionProvider(singleConnectionSession));
            assertEquals(new Document("_id", 999), cursor.next());

            singleConnectionSession.connection.close();
        } finally {
            connection.close();
        }
    }

    private ServerConnectionProvider getConnectionProvider(final Session session) {
        return session.createServerConnectionProvider(new ServerConnectionProviderOptions(true, new PrimaryServerSelector()));
    }

    private static class SingleConnectionSession implements Session {
        private final ServerDescription description;
        private final Connection connection;
        private boolean isClosed;

        public SingleConnectionSession(final ServerDescription description, final Connection connection) {
            this.description = description;
            this.connection = connection;
        }

        @Override
        public void close() {
            isClosed = true;
        }

        @Override
        public boolean isClosed() {
            return isClosed;
        }

        @Override
        public ServerConnectionProvider createServerConnectionProvider(final ServerConnectionProviderOptions options) {
            return new DelayedCloseServerConnectionProvider();
        }

        @Override
        public MongoFuture<ServerConnectionProvider> createServerConnectionProviderAsync(final ServerConnectionProviderOptions options) {
            return new SingleResultFuture<ServerConnectionProvider>(new DelayedCloseServerConnectionProvider(), null);
        }

        private class DelayedCloseServerConnectionProvider implements ServerConnectionProvider {
            @Override
            public ServerDescription getServerDescription() {
                return description;
            }

            @Override
            public Connection getConnection() {
                return new DelayedCloseConnection(connection);
            }

            @Override
            public MongoFuture<Connection> getConnectionAsync() {
                return new SingleResultFuture<Connection>(connection, null);
            }
        }
    }

    private static class DelayedCloseConnection implements Connection {
        private final Connection wrapped;
        private boolean isClosed;


        public DelayedCloseConnection(final Connection wrapped) {
            this.wrapped = notNull("wrapped", wrapped);
        }

        @Override
        public ServerAddress getServerAddress() {
            isTrue("open", !isClosed());
            return wrapped.getServerAddress();
        }

        @Override
        public ByteBuf getBuffer(final int capacity) {
            isTrue("open", !isClosed());
            return wrapped.getBuffer(capacity);
        }

        @Override
        public void sendMessage(final List<ByteBuf> byteBuffers, final int lastRequestId) {
            isTrue("open", !isClosed());
            wrapped.sendMessage(byteBuffers, lastRequestId);
        }

        @Override
        public ResponseBuffers receiveMessage(final int responseTo) {
            isTrue("open", !isClosed());
            return wrapped.receiveMessage(responseTo);
        }

        @Override
        public void sendMessageAsync(final List<ByteBuf> byteBuffers, final int lastRequestId, final SingleResultCallback<Void> callback) {
            isTrue("open", !isClosed());
            wrapped.sendMessageAsync(byteBuffers, lastRequestId, callback);
        }

        @Override
        public void receiveMessageAsync(final int responseTo,
                                        final SingleResultCallback<ResponseBuffers> callback) {
            isTrue("open", !isClosed());
            wrapped.receiveMessageAsync(responseTo, callback);
        }

        @Override
        public String getId() {
            return wrapped.getId();
        }

        @Override
        public void close() {
            isClosed = true;
        }

        @Override
        public boolean isClosed() {
            return isClosed;
        }
    }
}