package me.majhrs16.suite.iflow.vm.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The diagnostic {@code detail} must be carried by the error factories without
 * ever changing the HOST ABI registers ({@code R0}/{@code R3}/{@code R4}).
 */
class ServiceResultTest {

    @Test
    void okCarriesTransferredBytesAndScalar() {
        ServiceResult plain = ServiceResult.ok(5);
        assertEquals(ServiceResult.Status.OK, plain.status());
        assertEquals(5, plain.bytesTransferred());
        assertEquals(0, plain.result());
        assertTrue(plain.isSuccess());
        assertFalse(plain.hasDetail());

        ServiceResult scalar = ServiceResult.ok(3, 7);
        assertEquals(3, scalar.bytesTransferred());
        assertEquals(7, scalar.result());
    }

    @Test
    void partialIsSuccess() {
        ServiceResult partial = ServiceResult.partial(2, 4);
        assertEquals(ServiceResult.Status.PARTIAL, partial.status());
        assertTrue(partial.isSuccess());
        assertFalse(partial.isError());
    }

    @Test
    void messageFactoriesKeepTheirDiagnosticDetail() {
        assertEquals("bad arg", ServiceResult.invalidArgument("bad arg").detail());
        assertEquals("boom", ServiceResult.hostFailure("boom").detail());
        assertEquals("none", ServiceResult.notFound("none").detail());
        assertEquals("denied", ServiceResult.denied("denied").detail());
        assertEquals("nope", ServiceResult.unsupported("nope").detail());
        assertEquals("clash", ServiceResult.conflict("clash").detail());
    }

    @Test
    void bufferTooSmallExposesRequiredLengthAndDetail() {
        ServiceResult result = ServiceResult.bufferTooSmall(12);
        assertEquals(ServiceResult.Status.BUFFER_TOO_SMALL, result.status());
        assertEquals(12, result.bytesTransferred(), "R3 must carry the required size");
        assertTrue(result.hasDetail());
        assertTrue(result.detail().contains("12"), result.detail());
    }

    @Test
    void plainErrorFactoriesHaveNoDetail() {
        for (ServiceResult r : new ServiceResult[]{
                ServiceResult.notFound(), ServiceResult.denied(),
                ServiceResult.unsupported(), ServiceResult.conflict()}) {
            assertNull(r.detail());
            assertFalse(r.hasDetail());
            assertTrue(r.isError());
        }
    }

    @Test
    void detailNeverTouchesTheAbiRegisters() {
        // Same status factory, with and without a reason: the ABI fields match.
        ServiceResult with = ServiceResult.notFound("with reason");
        ServiceResult without = ServiceResult.notFound();
        assertEquals(without.status(), with.status());
        assertEquals(without.bytesTransferred(), with.bytesTransferred());
        assertEquals(without.result(), with.result());
    }

    @Test
    void toStringIncludesDetailWhenPresent() {
        assertFalse(ServiceResult.ok(1).toString().contains("detail"));
        assertTrue(ServiceResult.hostFailure("disk on fire").toString().contains("disk on fire"));
    }
}
