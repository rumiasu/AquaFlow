package com.example.aquaflow.service;
import com.example.aquaflow.util.InventoryCostMath;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InventoryCostMathTest {
 private BigDecimal m(String s){return new BigDecimal(s);}
 @Test void optionalAndExplicitZeroAreDifferent(){assertNull(InventoryCostMath.move(0,null,2,null).remaining());assertEquals(m("0.00"),InventoryCostMath.move(0,null,2,m("0.00")).remaining());}
 @Test void completeMixedPurchasesPreserveTotalAndAllocateAverage(){var a=InventoryCostMath.move(2,m("10.00"),2,m("20.00"));assertEquals(m("30.00"),a.remaining());var b=InventoryCostMath.move(4,a.remaining(),-3,null);assertEquals(m("22.50"),b.movement());assertEquals(m("7.50"),b.remaining());}
 @Test void unknownStockCannotBeRepairedByNewPrice(){assertNull(InventoryCostMath.move(2,null,3,m("30.00")).remaining());assertNull(InventoryCostMath.move(5,null,-2,null).movement());assertEquals(BigDecimal.ZERO,InventoryCostMath.move(3,null,-3,null).remaining());assertEquals(m("20.00"),InventoryCostMath.move(0,null,2,m("20.00")).remaining());}
 @Test void lastUnitRetainsRoundingRemainder(){var a=InventoryCostMath.move(3,m("1.00"),-1,null);var b=InventoryCostMath.move(2,a.remaining(),-1,null);var c=InventoryCostMath.move(1,b.remaining(),-1,null);assertEquals(m("1.00"),a.movement().add(b.movement()).add(c.movement()));assertEquals(m("0.00"),c.remaining());}
 @Test void invalidMoneyRejectedWithoutRounding(){assertThrows(IllegalArgumentException.class,()->InventoryCostMath.money(m("-1")));assertThrows(IllegalArgumentException.class,()->InventoryCostMath.money(m("1.001")));assertEquals(m("0.00"),InventoryCostMath.money(m("0")));assertNull(InventoryCostMath.money(null));}
}
