/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.jersey2.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.jersey2.server.resources.TimedOnClassResource;
import io.micrometer.jersey2.server.resources.TimedResource;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Test;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.*;

/**
 * @author Michael Weirauch
 */
public class MetricsRequestEventListenerTimedTest extends JerseyTest {

    static {
        Logger.getLogger("org.glassfish.jersey").setLevel(Level.OFF);
    }

    private static final String METRIC_NAME = "http.server.requests";

    private MeterRegistry registry;

    private CountDownLatch longTaskRequestStartedLatch;

    private CountDownLatch longTaskRequestReleaseLatch;

    @Override
    protected Application configure() {
        registry = new SimpleMeterRegistry();
        longTaskRequestStartedLatch = new CountDownLatch(1);
        longTaskRequestReleaseLatch = new CountDownLatch(1);

        final MetricsApplicationEventListener listener = new MetricsApplicationEventListener(
            registry, new DefaultJerseyTagsProvider(), METRIC_NAME, false);

        final ResourceConfig config = new ResourceConfig();
        config.register(listener);
        config.register(
            new TimedResource(longTaskRequestStartedLatch, longTaskRequestReleaseLatch));
        config.register(TimedOnClassResource.class);

        return config;
    }

    @Test
    public void resourcesAndNotFoundsAreNotAutoTimed() {
        target("not-timed").request().get();
        target("not-found").request().get();

        assertThat(registry.find(METRIC_NAME)
            .tags(tagsFrom("/not-timed", 200)).timer()).isEmpty();

        assertThat(registry.find(METRIC_NAME)
            .tags(tagsFrom("NOT_FOUND", 404)).timer()).isEmpty();
    }

    @Test
    public void resourcesWithAnnotationAreTimed() {
        target("timed").request().get();
        target("multi-timed").request().get();

        assertThat(registry.find(METRIC_NAME)
            .tags(tagsFrom("/timed", 200)).timer())
            .hasValueSatisfying(t -> assertThat(t.count()).isEqualTo(1));

        assertThat(registry.find("multi1")
            .tags(tagsFrom("/multi-timed", 200)).timer())
            .hasValueSatisfying(t -> assertThat(t.count()).isEqualTo(1));

        assertThat(registry.find("multi2")
            .tags(tagsFrom("/multi-timed", 200)).timer())
            .hasValueSatisfying(t -> assertThat(t.count()).isEqualTo(1));
    }

    @Test
    public void longTaskTimerSupported() throws InterruptedException, ExecutionException {
        final Future<Response> future = target("long-timed").request().async().get();

        /*
         * Wait until the request has arrived at the server side. (Async client
         * processing might be slower in triggering the request resulting in the
         * assertions below to fail. Thread.sleep() is not an option, so resort
         * to CountDownLatch.)
         */
        longTaskRequestStartedLatch.await();

        // the request is not timed, yet
        assertThat(registry.find(METRIC_NAME).tags(tagsFrom("/timed", 200)).timer())
            .isEmpty();

        // the long running task is timed
        assertThat(registry.find("long.task.in.request")
            .tags(Tags.zip(DefaultJerseyTagsProvider.TAG_METHOD, "GET",
                DefaultJerseyTagsProvider.TAG_URI, "/long-timed"))
            .value(Statistic.Count, 1.0)
            .longTaskTimer()).isPresent();

        // finish the long running request
        longTaskRequestReleaseLatch.countDown();
        future.get();

        // the request is timed after the long running request completed
        assertThat(registry.find(METRIC_NAME)
            .tags(tagsFrom("/long-timed", 200))
            .value(Statistic.Count, 1.0)
            .timer()).isPresent();
    }

    @Test
    public void unnamedLongTaskTimerIsNotSupported() {
        try {
            target("long-timed-unnamed").request().get();
            failBecauseExceptionWasNotThrown(IllegalArgumentException.class);
        } catch(ProcessingException e) {
            assertThatIllegalArgumentException();
        }
    }

    @Test
    public void classLevelAnnotationIsInherited() {
        target("/class/inherited").request().get();

        assertThat(
            registry.find(METRIC_NAME)
                .tags(Tags.concat(tagsFrom("/class/inherited", 200),
                    Tags.zip("on", "class")))
                .value(Statistic.Count, 1.0).timer()).isPresent();
    }

    @Test
    public void methodLevelAnnotationOverridesClassLevel() {
        target("/class/on-method").request().get();

        assertThat(
            registry.find(METRIC_NAME)
                .tags(Tags.concat(tagsFrom("/class/on-method", 200),
                    Tags.zip("on", "method")))
                .value(Statistic.Count, 1.0).timer()).isPresent();

        // class level annotation is not picked up
        assertThat(registry.getMeters()).hasSize(1);
    }

    private static Iterable<Tag> tagsFrom(String uri, int status) {
        return Tags.zip(DefaultJerseyTagsProvider.TAG_METHOD, "GET",
            DefaultJerseyTagsProvider.TAG_URI, uri, DefaultJerseyTagsProvider.TAG_STATUS,
            String.valueOf(status), DefaultJerseyTagsProvider.TAG_EXCEPTION, "None");
    }
}
