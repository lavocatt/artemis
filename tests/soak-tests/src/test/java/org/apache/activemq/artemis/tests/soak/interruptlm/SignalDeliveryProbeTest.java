/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.tests.soak.interruptlm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Diagnostic test to verify signal delivery mechanisms inside the CI container.
 * This is a throwaway test to prove whether /usr/bin/kill exists and whether
 * Runtime.exec("kill -SIGINT ...") actually delivers signals.
 */
public class SignalDeliveryProbeTest {

   @Test
   public void probeSignalDelivery() throws Exception {
      System.out.println("=== SignalDeliveryProbe: CI environment check ===");
      System.out.println("PID: " + ProcessHandle.current().pid());

      boolean binKill = new File("/bin/kill").exists();
      boolean usrBinKill = new File("/usr/bin/kill").exists();
      System.out.println("/bin/kill exists: " + binKill);
      System.out.println("/usr/bin/kill exists: " + usrBinKill);

      // Spawn a child process
      Process child = new ProcessBuilder("sleep", "300").start();
      long childPid = child.pid();
      System.out.println("Child PID: " + childPid + ", alive: " + child.isAlive());

      // Test 1: Runtime.exec("kill -SIGINT <pid>") — the broken mechanism
      System.out.println("\n--- Runtime.exec(\"kill -SIGINT " + childPid + "\") ---");
      try {
         Process killProc = Runtime.getRuntime().exec("kill -SIGINT " + childPid);
         boolean finished = killProc.waitFor(5, TimeUnit.SECONDS);
         System.out.println("kill process started: true");
         System.out.println("kill process finished: " + finished);
         if (finished) {
            System.out.println("kill exit code: " + killProc.exitValue());
            String stderr = new String(killProc.getErrorStream().readAllBytes()).trim();
            if (!stderr.isEmpty()) {
               System.out.println("kill stderr: " + stderr);
            }
         }
         boolean childDied = child.waitFor(3, TimeUnit.SECONDS);
         System.out.println("child exited after Runtime.exec SIGINT: " + childDied);
      } catch (Exception e) {
         System.out.println("kill process started: false — " + e.getMessage());
      }

      // Cleanup first child if still alive, spawn new one for test 2
      if (child.isAlive()) child.destroyForcibly();

      Process child2 = new ProcessBuilder("sleep", "300").start();
      long child2Pid = child2.pid();

      // Test 2: process.destroy() — the proposed fix
      System.out.println("\n--- process.destroy() on PID " + child2Pid + " ---");
      child2.destroy();
      boolean child2Died = child2.waitFor(3, TimeUnit.SECONDS);
      System.out.println("child exited after destroy(): " + child2Died);

      if (child2.isAlive()) child2.destroyForcibly();

      System.out.println("\n=== Probe complete ===");
      System.out.println("VERDICT: kill binary present = " + (binKill || usrBinKill));

      // This test always passes — it's purely diagnostic
      assertTrue(true);
   }
}
