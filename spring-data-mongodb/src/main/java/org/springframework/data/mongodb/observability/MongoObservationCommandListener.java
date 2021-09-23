/*
 * Copyright 2013-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb.observability;

import io.micrometer.api.instrument.Tag;
import io.micrometer.api.instrument.observation.Observation;
import io.micrometer.api.instrument.observation.ObservationRegistry;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.springframework.lang.Nullable;

import com.mongodb.RequestContext;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ConnectionId;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;

/**
 * Implement MongoDB's {@link CommandListener} using Micrometer's {@link Observation} API.
 *
 * @see https://github.com/openzipkin/brave/blob/release-5.13.0/instrumentation/mongodb/src/main/java/brave/mongodb/TraceMongoCommandListener.java
 * @author OpenZipkin Brave Authors
 * @author Marcin Grzejszczak
 * @author Greg Turnquist
 * @since 4.0.0
 */
public final class MongoObservationCommandListener implements CommandListener {

	private static final Log log = LogFactory.getLog(MongoObservationCommandListener.class);

	/**
	 * @see https://docs.mongodb.com/manual/reference/command for the command reference
	 */
	static final Set<String> COMMANDS_WITH_COLLECTION_NAME = new LinkedHashSet<>(Arrays.asList("aggregate", "count",
			"distinct", "mapReduce", "geoSearch", "delete", "find", "findAndModify", "insert", "update", "collMod", "compact",
			"convertToCapped", "create", "createIndexes", "drop", "dropIndexes", "killCursors", "listIndexes", "reIndex"));

	private final ObservationRegistry registry;

	public MongoObservationCommandListener(ObservationRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Extract the {@link Observation} from MongoDB's {@link RequestContext}.
	 * 
	 * @param context
	 * @return
	 */
	private static Observation observationFromContext(RequestContext context) {

		Observation observation = context.getOrDefault(Observation.class, null);

		if (observation != null) {
			if (log.isDebugEnabled()) {
				log.debug("Found a observation in mongo context [" + observation + "]");
			}
			return observation;
		}

		if (log.isDebugEnabled()) {
			log.debug("No observation was found - will not create any child spans");
		}

		return null;
	}

	/**
	 * @return trimmed string from {@code bsonValue} or null if the trimmed string was empty or the value wasn't a string
	 */
	@Nullable
	static String getNonEmptyBsonString(BsonValue bsonValue) {

		if (bsonValue == null || !bsonValue.isString()) {
			return null;
		}

		String stringValue = bsonValue.asString().getValue().trim();

		return stringValue.isEmpty() ? null : stringValue;
	}

	static String getMetricName(String commandName, @Nullable String collectionName) {
		return collectionName == null ? commandName : commandName + " " + collectionName;
	}

	@Override
	public void commandStarted(CommandStartedEvent event) {

		if (log.isDebugEnabled()) {
			log.debug("Instrumenting the command started event");
		}

		String databaseName = event.getDatabaseName();

		if ("admin".equals(databaseName)) {
			return; // don't instrument commands like "endSessions"
		}

		RequestContext requestContext = event.getRequestContext();

		if (requestContext == null) {
			return;
		}

		Observation parent = observationFromContext(requestContext);

		if (log.isDebugEnabled()) {
			log.debug("Found the following observation passed from the mongo context [" + parent + "]");
		}

		if (parent == null) {
			return;
		}

		setupObservability(event, requestContext);
	}

	private void setupObservability(CommandStartedEvent event, RequestContext requestContext) {

		String commandName = event.getCommandName();
		BsonDocument command = event.getCommand();
		String collectionName = getCollectionName(commandName, command);
		MongoHandlerContext mongoHandlerContext = new MongoHandlerContext(event, requestContext);

		Observation observation = MongoObservation.MONGODB_COMMAND_OBSERVATION
				.observation(this.registry, mongoHandlerContext).contextualName(getMetricName(commandName, collectionName));

		if (collectionName != null) {
			observation.lowCardinalityTag(MongoObservation.LowCardinalityCommandTags.MONGODB_COLLECTION.of(collectionName));
		}

		Tag tag = connectionTag(event);

		if (tag != null) {
			observation.lowCardinalityTag(tag);
		}

		observation.highCardinalityTag(MongoObservation.HighCardinalityCommandTags.MONGODB_COMMAND.of(commandName));

		requestContext.put(Observation.class, observation.start());
		requestContext.put(MongoHandlerContext.class, mongoHandlerContext);

		if (log.isDebugEnabled()) {
			log.debug(
					"Created a child observation  [" + observation + "] for mongo instrumentation and put it in mongo context");
		}
	}

	private Tag connectionTag(CommandStartedEvent event) {

		ConnectionDescription connectionDescription = event.getConnectionDescription();

		if (connectionDescription != null) {

			ConnectionId connectionId = connectionDescription.getConnectionId();
			if (connectionId != null) {
				return MongoObservation.LowCardinalityCommandTags.MONGODB_CLUSTER_ID
						.of(connectionId.getServerId().getClusterId().getValue());
			}
		}

		return null;
	}

	@Override
	public void commandSucceeded(CommandSucceededEvent event) {

		if (event.getRequestContext() == null) {
			return;
		}

		Observation observation = event.getRequestContext().getOrDefault(Observation.class, null);
		if (observation == null) {
			return;
		}

		MongoHandlerContext context = event.getRequestContext().get(MongoHandlerContext.class);
		context.setCommandSucceededEvent(event);

		if (log.isDebugEnabled()) {
			log.debug("Command succeeded - will stop observation [" + observation + "]");
		}

		observation.stop();
	}

	@Override
	public void commandFailed(CommandFailedEvent event) {

		if (event.getRequestContext() == null) {
			return;
		}

		Observation observation = event.getRequestContext().getOrDefault(Observation.class, null);
		if (observation == null) {
			return;
		}

		MongoHandlerContext context = event.getRequestContext().get(MongoHandlerContext.class);
		context.setCommandFailedEvent(event);

		if (log.isDebugEnabled()) {
			log.debug("Command failed - will stop observation [" + observation + "]");
		}

		observation.error(event.getThrowable());
		observation.stop();
	}

	/**
	 * Transform the command name into a collection name;
	 * 
	 * @param command
	 * @param commandName
	 * @return
	 */
	@Nullable
	private String getCollectionName(String commandName, BsonDocument command) {

		if (COMMANDS_WITH_COLLECTION_NAME.contains(commandName)) {

			String collectionName = getNonEmptyBsonString(command.get(commandName));

			if (collectionName != null) {
				return collectionName;
			}
		}

		// Some other commands, like getMore, have a field like {"collection": collectionName}.
		return getNonEmptyBsonString(command.get("collection"));
	}
}
