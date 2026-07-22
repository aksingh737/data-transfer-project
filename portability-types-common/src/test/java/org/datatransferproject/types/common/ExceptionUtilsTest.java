package org.datatransferproject.types.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExceptionUtilsTest {

  @Test
  public void testNull() {
    assertEquals("", ExceptionUtils.getStackTraceAsString(null));
  }

  @Test
  public void testNormalException() {
    Exception e = new Exception("Test exception");
    String resolved = ExceptionUtils.getStackTraceAsString(e);
    assertTrue(resolved.contains("java.lang.Exception: Test exception"));
  }

  @Test
  public void testDuplicateFramesCollapsed() {
    Exception e = createRecursiveException(5);
    String resolved = ExceptionUtils.getStackTraceAsString(e);
    // 5 recursive frames means 1 base condition frame and 4 identical recursion frames.
    // The first recursion frame is printed, and the remaining 3 are collapsed.
    System.out.println(resolved); // for debugging
    assertTrue(resolved.contains("... repeated 3 times more"));
  }

  private Exception createRecursiveException(int depth) {
    if (depth <= 1) {
      return new Exception("Recursive error");
    }
    return createRecursiveException(depth - 1);
  }
}
