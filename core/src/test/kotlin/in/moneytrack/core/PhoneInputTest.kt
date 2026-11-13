package `in`.moneytrack.core
import org.junit.Assert.*
import org.junit.Test

class PhoneInputTest {
 @Test fun acceptsNationalNumber(){assertEquals("+919876543210",PhoneInput.normalize("9876543210"))}
 @Test fun acceptsExplicitCountryCode(){assertEquals("+919876543210",PhoneInput.normalize("+919876543210"));assertEquals("+919876543210",PhoneInput.normalize("919876543210"))}
 @Test fun acceptsDisplaySeparators(){assertEquals("+919876543210",PhoneInput.normalize(" (+91) 98765-43210 "))}
 @Test fun rejectsMalformedOrForeignNumbers(){
  listOf("","+91","1234567890","987654321","98765432100","+449876543210","98a76543210","++919876543210","09876543210").forEach { value->
   try {PhoneInput.normalize(value);fail("Must reject malformed phone input")}catch(expected:IllegalArgumentException){assertTrue(expected.message!!.contains("10-digit"))}
  }
 }
}
