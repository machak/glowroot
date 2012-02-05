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

import java.util.Random;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExpensiveCall {

    private static final Random random = new Random();

    private final int maxTimeMillis;
    private final int maxDescriptionLength;

    public ExpensiveCall(int maxTimeMillis, int maxDescriptionLength) {
        this.maxTimeMillis = maxTimeMillis;
        this.maxDescriptionLength = maxDescriptionLength;
    }

    public void execute() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public String getDescription() {
        int descriptionLength = random.nextInt(maxDescriptionLength);
        StringBuffer sb = new StringBuffer(descriptionLength);
        for (int i = 0; i < descriptionLength; i++) {
            if (random.nextInt(6) == 0) {
                // on average, one of six characters will be a space
                sb.append(' ');
            } else {
                // the rest will be random lowercase characters
                sb.append((char) ('a' + random.nextInt(26)));
            }
        }
        return sb.toString();
    }
}