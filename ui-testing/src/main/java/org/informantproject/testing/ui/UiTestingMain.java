/**
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.informantproject.testing.ui;

import org.informantproject.core.util.Static;
import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Config.CoarseProfilingConfig;
import org.informantproject.testkit.Config.CoreConfig;
import org.informantproject.testkit.Config.FineProfilingConfig;
import org.informantproject.testkit.InformantContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public final class UiTestingMain {

    private static final int UI_PORT = 4000;

    private static final Logger logger = LoggerFactory.getLogger(UiTestingMain.class);

    public static void main(String... args) throws Exception {
        InformantContainer container = InformantContainer.create(UI_PORT, false);
        // set thresholds low so there will be lots of data to view
        CoreConfig coreConfig = container.getInformant().getCoreConfig();
        coreConfig.setPersistenceThresholdMillis(0);
        coreConfig.setSpanStackTraceThresholdMillis(100);
        container.getInformant().updateCoreConfig(coreConfig);
        CoarseProfilingConfig coarseProfilingConfig = container.getInformant()
                .getCoarseProfilingConfig();
        coarseProfilingConfig.setInitialDelayMillis(500);
        coarseProfilingConfig.setIntervalMillis(500);
        coarseProfilingConfig.setTotalSeconds(2);
        container.getInformant().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getInformant().getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(50);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setTotalSeconds(1);
        container.getInformant().updateFineProfilingConfig(fineProfilingConfig);
        logger.info("view trace ui at localhost:" + UI_PORT + "/traces.html");
        container.executeAppUnderTest(GenerateTraces.class);
    }

    public static class GenerateTraces implements AppUnderTest {
        public void executeApp() throws InterruptedException {
            while (true) {
                // one very short trace that will have an empty merged stack tree
                new NestableCall(1, 10, 100).execute();
                new NestableCall(new NestableCall(10, 50, 5000), 20, 50, 5000).execute();
                new NestableCall(new NestableCall(50, 50, 5000), 100, 50, 5000).execute();
                new NestableCall(new NestableCall(5, 50, 5000), 5, 50, 5000).execute();
                new NestableCall(new NestableCall(10, 50, 5000), 10, 50, 5000).execute();
                new NestableCall(new NestableCall(20, 50, 5000), 5, 50, 5000).execute();
                Thread.sleep(10000);
            }
        }
    }

    private UiTestingMain() {}
}