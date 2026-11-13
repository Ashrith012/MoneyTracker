package `in`.moneytrack.core

/** Normalize display formatting without silently removing invalid characters. */
object PhoneInput {
 fun normalize(input:String):String {
  val compact=input.filterNot { it.isWhitespace() || it=='-' || it=='(' || it==')' }
  val national=when {
   compact.startsWith("+91")->compact.drop(3)
   compact.length==12 && compact.startsWith("91")->compact.drop(2)
   else->compact
  }
  require(Regex("[6-9][0-9]{9}").matches(national)) {
   "Enter a valid 10-digit Indian mobile number, with or without +91."
  }
  return "+91$national"
 }
}
