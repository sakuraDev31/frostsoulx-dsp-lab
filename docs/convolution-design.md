# Resonance Convolution Design

## Decision

Resonance now includes an optional **uniform partitioned FFT convolver** in the reference engine. The existing parametric chain remains the default path. The convolution mix defaults to zero, so existing playback is unchanged until the control is raised.

The convolver uses 256-sample partitions and 512-point FFTs. Its working buffers, impulse-response partitions, history, overlap storage, and delay buffers are allocated during engine creation. The audio callback performs only bounded arithmetic and buffer access; it does not allocate, lock, read files, or rebuild DSP objects.

A short deterministic room impulse response is compiled into the reference engine. This is intentionally not a user-file loader. The current plugin ABI has no safe asynchronous IR-loading API, and reading or decoding an IR inside `ae_process` would violate the real-time contract. A future external IR loader should prepare and validate a new convolver off the audio thread and publish it through an ownership hand-off before processing.

## Controls

The manifest exposes only controls that reach the native implementation:

- **IR convolution mix** blends the convolved signal with the existing parametric output.
- **IR pre-delay** selects a bounded 0–20 ms ring-buffer delay on the wet path.
- **IR damping** attenuates the wet result before mixing.

The automatically generated Sound screen reads these manifest parameters, so no second UI-specific DSP implementation is needed.

## Performance and latency

Partitioned convolution avoids the cost of direct time-domain convolution for a multi-thousand-sample IR. A 256-sample partition keeps the transform size bounded and makes the cost predictable. The convolver introduces up to one partition of algorithmic buffering when enabled. The existing look-ahead limiter remains in the chain and contributes its existing latency.

The implementation deliberately uses a fixed short IR rather than a large non-uniform tail engine. Non-uniform partitioning can reduce CPU for very long reverberation IRs, but it adds scheduling and state complexity that is not justified for the current lightweight mobile reference engine. It can be added later behind the same plugin ABI if real IR sizes require it.

## Validation

Host tests cover bypass transparency, measurable convolution tail, damping and pre-delay audibility, finite output under extreme settings, multiple block sizes and sample rates, reset behavior, manifest validity, and bridge parity through `dlopen`.

## References

[1]: https://thewolfsound.com/fast-convolution-fft-based-overlap-add-overlap-save-partitioned/ "Fast Convolution: FFT-based, Overlap-Add, Overlap-Save, and Partitioned"
[2]: https://docs.juce.com/master/classjuce_1_1dsp_1_1Convolution.html "JUCE dsp::Convolution Class Reference"
[3]: https://github.com/neodsp/fft-convolver "neodsp fft-convolver: Fast, real-time safe FFT-based convolution"
