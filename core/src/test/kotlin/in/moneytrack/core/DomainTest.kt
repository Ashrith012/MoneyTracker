package `in`.moneytrack.core
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class DomainTest {
 private val stamp=Instant.parse("2026-11-10T10:00:00Z").toEpochMilli()
 private val month=YearMonth.of(2026,11)
 private fun row(id:String="a",amount:Long=10000,direction:Direction=Direction.DEBIT,status:Status=Status.SUCCESSFUL)=Transaction(id=id,reference="REF",amount=amount,direction=direction,occurredAt=stamp,receivedAt=stamp,ingestedAt=stamp,account="****1234",merchant="Shop",status=status,sources=setOf(id))
 private fun fixture(extra:String="")="MONEYTRACK-SYNTHETIC-V1\namount=123.45\ndirection=DEBIT\nstatus=SUCCESSFUL\naccount=****1234\n$extra"
 @Test fun exactMinorUnits(){assertEquals(12345,SyntheticParser().parse(Email("m",stamp,fixture()))!!.amount)}
 @Test fun rejectPromotionsOtpAndRequests(){listOf("Your OTP is 123456","Pay INR 500 now","You won cashback","Payment failed").forEach {assertNull(SyntheticParser().parse(Email("m",stamp,it)))}}
 @Test fun unsupportedFraction(){assertNull(SyntheticParser().parse(Email("m",stamp,fixture("amount=0.001"))))}
 @Test fun missingTimeIsNotInvented(){assertNull(SyntheticParser().parse(Email("m",stamp,fixture()))!!.occurredAt)}
 @Test fun duplicateMessageIsIdempotent(){val a=row();assertEquals(listOf(a),Reconcile.ingest(listOf(a),a))}
 @Test fun pendingBecomesSuccessful(){val a=row(status=Status.PENDING);val b=row("b");val merged=Reconcile.ingest(listOf(a),b).single();assertEquals(Status.SUCCESSFUL,merged.status);assertEquals(setOf("a","b"),merged.sources)}
 @Test fun delayedPendingDoesNotDowngrade(){val merged=Reconcile.ingest(listOf(row()),row("b",status=Status.PENDING)).single();assertEquals(Status.SUCCESSFUL,merged.status);assertTrue(merged.review)}
 @Test fun missingReferenceIsNotAutoMerged(){val a=row().copy(reference=null);val b=row("b").copy(reference=null);val result=Reconcile.ingest(listOf(a),b);assertEquals(2,result.size);assertTrue(result.last().review)}
 @Test fun differentAccountNotMerged(){assertEquals(2,Reconcile.ingest(listOf(row()),row("b").copy(account="****9999")).size)}
 @Test fun transferKeepsBothSides(){val a=row();val b=row("b",direction=Direction.CREDIT).copy(account="****9999");val pair=Reconcile.transfer(a,b);assertEquals(0,Analytics.totals(listOf(pair.first,pair.second),month).net);assertNotEquals(pair.first.id,pair.second.id)}
 @Test fun failedAndPendingExcluded(){val totals=Analytics.totals(listOf(row(status=Status.FAILED),row("p",status=Status.PENDING)),month);assertEquals(0,totals.spending);assertEquals(10000,totals.pending)}
 @Test fun partialRefundReducesSpendingNotIncome(){val d=row();val credit=Reconcile.refund(d,row("r",amount=2500,direction=Direction.CREDIT));val totals=Analytics.totals(listOf(d,credit),month);assertEquals(7500,totals.spending);assertEquals(0,totals.income)}
 @Test fun reversalAndCreditNotCountedTwice(){val d=row(status=Status.REVERSED);val credit=Reconcile.refund(d,row("r",direction=Direction.CREDIT));assertEquals(0,Analytics.totals(listOf(d,credit),month).net)}
 @Test fun creditNotAutomaticallyARefund(){assertEquals(10000,Analytics.totals(listOf(row("c",direction=Direction.CREDIT)),month).income)}
 @Test fun duplicateExclusionRetainsEvidence(){val t=row().copy(excludedDuplicate=true);assertEquals(0,Analytics.totals(listOf(t),month).spending);assertEquals(setOf("a"),t.sources)}
 @Test fun forecastRequiresCoverage(){assertNull(Analytics.forecast(listOf(row()),month,null));assertNull(Analytics.forecast(listOf(row()),month,stamp))}
 @Test fun balanceDoesNotDoubleCountReversalCredit(){val d=row(status=Status.REVERSED);val credit=Reconcile.refund(d,row("r",direction=Direction.CREDIT));assertEquals(0L,Analytics.movement(listOf(d,credit),0))}
 @Test fun forecastWithThreeMonthsOfCoverage(){val rows=(1L..3L).map { offset->row("m$offset",amount=offset*10000).copy(occurredAt=month.minusMonths(offset).atDay(10).atStartOfDay(Analytics.zone).toInstant().toEpochMilli())};val start=month.minusMonths(3).atDay(1).atStartOfDay(Analytics.zone).toInstant().toEpochMilli();assertEquals(20000L,Analytics.forecast(rows,month,start))}
 @Test fun refundAlsoReducesCategory(){val debit=row();val credit=Reconcile.refund(debit,row("credit",amount=2500,direction=Direction.CREDIT));assertEquals(7500L,Analytics.categories(listOf(debit,credit),month)["Other"])}
 @Test fun currencyFormatting(){assertEquals("₹1,23,456.78",money(12345678));assertEquals("₹0.01",money(1))}
 @Test fun assistantUsesRecords(){assertTrue(Assistant.answer("How much did I spend?",listOf(row()),month).contains("₹100.00"))}
 @Test fun unknownFailureCause(){assertTrue(Assistant.answer("Why did payment fail?",emptyList(),month).contains("does not confirm"))}
}
