package vn.chamcong.iot.model
import org.junit.Assert.*
import org.junit.Test
class PersonnelRulesTest {
 @Test fun skipsExistingCodes() { assertEquals("NV0013", nextEmployeeCode(3, listOf("NV0012", "OLD-99"))) }
 @Test fun neverReusesRetiredSequence() { assertEquals("NV0101", nextEmployeeCode(100, listOf("NV0001"))) }
 @Test fun snapshotSurvivesChanges() {
  val e = Employee(id="a", code="NV0001", fullName="An", baseSalary=8000000)
  val slip = createPayroll(e, "2026-09", 500000, 200000)
  assertFalse(e.copy(active=false, baseSalary=9000000).active)
  assertEquals(8300000L, slip.netSalary)
  assertEquals("An", slip.employeeName)
  assertEquals(8000000L, slip.baseSalary)
 }
 @Test(expected=IllegalArgumentException::class) fun invalidMonth() { createPayroll(Employee(id="a"), "2026-13", 0, 0) }
 @Test(expected=IllegalArgumentException::class) fun negativeBonus() { createPayroll(Employee(id="a"), "2026-09", -1, 0) }
}
