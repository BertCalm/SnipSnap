#!/usr/bin/env python3
"""Reproducible JUCE-free extraction. Originals remain immutable in upstream/."""
from pathlib import Path
import re
root=Path(__file__).resolve().parent
src=(root/'upstream/Source/DSP/Effects/AquaticFXSuite.h').read_text()
layout=src[src.index('inline void AquaticFXSuite::addParameters'):]
metadata={m[0]:(float(m[1]),float(m[2]),float(m[3])) for m in re.findall(r'layout.add\(std::make_unique<AP>\("([^"]+)",[^\n]+?NR\(([-.\d]+)f, ([-.\d]+)f,[^\n]+?\), ([-.\d]+)f\)\);', layout)}
cache=src[src.index('inline void AquaticFXSuite::cacheParameterPointers'):src.index('inline void AquaticFXSuite::addParameters')]
ids=dict(re.findall(r'params\.(\w+) = get\("([^"]+)"\);',cache))
# Cached code calls the high crossover aqua_fathom_hiXover but layout spells highXover.
ids['fathomHiXover']='aqua_fathom_highXover'
defaults=dict(re.findall(r'params\.(\w+) \? params\.\w+->load\(\) : ([-.\d]+)f', src))
params='    struct Parameters\n    {\n'+''.join(f'        float {member} = {defaults.get(member, str(metadata[key][2]))}f;\n' for member,key in ids.items())+'    } params;\n'
params=params.replace(' = 0.07f;', ' = 0.07f;')
start=src.index('    //==========================================================================\n    // Cached parameter pointers')
end=src.index('    double sr =', start)
src=src[:start]+params+src[end:]
src=src[:src.index('inline void AquaticFXSuite::cacheParameterPointers')]+ '\n} // namespace xoceanus\n'
src=src.replace('#include <juce_audio_processors/juce_audio_processors.h>', '#include <algorithm>\n#include <string>\n#include <stdexcept>\n#include <cstdint>')
src=src.replace('    void cacheParameterPointers(juce::AudioProcessorValueTreeState& apvts);','    void setParameter(const std::string& id, float value);\n    void setSeed(uint32_t seed);')
src=src.replace('    static void addParameters(juce::AudioProcessorValueTreeState::ParameterLayout& layout);','')
src=src.replace('    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(AquaticFXSuite)', '')
src=src.replace('    juce::ScopedNoDenormals noDenormals; // Issue #902: FTZ/DAZ protection (missed in original Wave 1 chains)', '')
src=src.replace('juce::jlimit(1, len - 1, static_cast<int>(kPrimeLengths48k[ch] * srScale * size * 2.7f + 1))', 'std::clamp(static_cast<int>(kPrimeLengths48k[ch] * srScale * size * 2.7f + 1), 1, len - 1)')
src=re.sub(r'params\.(\w+) \? params\.\w+->load\(\) : [-.\d]+f',r'params.\1',src)
# Validation is host side; no atomics/JUCE are needed in this offline rendering tool.
setters='\ninline void AquaticFXSuite::setParameter(const std::string& id, float value)\n{\n    if (!std::isfinite(value)) throw std::invalid_argument("Non-finite parameter: " + id);\n'
for member,key in ids.items():
    lo,hi,_=metadata[key]
    setters+=f'    if (id == "{key}") {{ params.{member} = std::clamp(value, {lo}f, {hi}f); return; }}\n'
setters+='    throw std::invalid_argument("Unknown parameter: " + id);\n}\n\ninline void AquaticFXSuite::setSeed(uint32_t seed)\n{\n    drift.initialSeed = seed ? seed : 1u;\n    tide.initialSeed = (seed ^ 0xA5A5A5A5u) ? (seed ^ 0xA5A5A5A5u) : 1u;\n}\n'
src=src.replace('\n} // namespace xoceanus', setters+'\n} // namespace xoceanus')
# Correct SVF damping direction/prewarp and observable shelf/bell transfer functions.
src=src.replace('float g = fastTan(pi * fc / static_cast<float>(sampleRate));\n            // Clamp g to avoid instability at extreme frequencies\n            g = std::max(0.0001f, std::min(g, 0.9999f));\n            k = 0.02f + resonance * 1.98f; // [0.02, 2.0]', 'fc = std::clamp(fc, 20.0f, static_cast<float>(sampleRate) * 0.45f);\n            float g = std::tan(pi * fc / static_cast<float>(sampleRate));\n            k = 2.0f - std::clamp(resonance, 0.0f, 1.0f) * 1.98f;')
src=src.replace('sqrtGain = fastPow2(gainDb * (1.0f / 12.411f)); // 10^(dB/40)', 'sqrtGain = std::sqrt(linGain);')
src=src.replace('float hp = v3 - k * v1 - v2;', 'float hp = x - k * v1 - v2;')
src=src.replace('return hp + sqrtGain * bp + linGain * lp;', '(void)hp; (void)bp; (void)lp;\n            return linGain * x + k * (1.0f - sqrtGain) * sqrtGain * v1 + (1.0f - linGain) * v2;')
src=src.replace('return linGain * (hp + sqrtGain * bp) + lp;', '(void)hp; (void)bp; (void)lp;\n            return x + k * (sqrtGain - 1.0f) * v1 + (linGain - 1.0f) * v2;')
src=src.replace('return x + (bellGainLin - k) * v1;', 'return x + k * (bellGainLin - 1.0f) * v1;')
src=src.replace('// High shelf: output = HP + sqrt(gain)*BP + gain*LP', '// Corrected TPT SVF high shelf: DC=1, Nyquist=linear gain; zero dB=x.')
src=src.replace('// Low shelf: gain*(HP + sqrt(gain)*BP) + LP', '// Corrected TPT SVF low shelf: DC=linear gain, Nyquist=1; zero dB=x.')
src=src.replace('// Bell: input + (bellGain - k) * BP', '// Bell: input + k*(linear gain - 1)*BP; zero dB=x.')

# Gain-dependent prewarp restores the specified shelf midpoint (half dB gain at fc).
src=src.replace('void updateCoeffs(float fc, float resonance, double sampleRate)', 'void updateCoeffs(float fc, float resonance, double sampleRate, float gainPrewarp = 1.0f)')
src=src.replace('float g = std::tan(pi * fc / static_cast<float>(sampleRate));', 'float g = std::tan(pi * fc / static_cast<float>(sampleRate)) * gainPrewarp;')
src=src.replace('                svfL.updateCoeffs(freqSmoothed, res, sr);\n                svfR.updateCoeffs(freqSmoothed, res, sr);\n                svfL.updateGain(gainSmoothed);\n                svfR.updateGain(gainSmoothed);', '                svfL.updateGain(gainSmoothed);\n                svfR.updateGain(gainSmoothed);\n                float shelfPrewarp = (mode == Mode::HighShelf) ? std::sqrt(svfL.sqrtGain) :\n                                     (mode == Mode::LowShelf) ? 1.0f / std::sqrt(svfL.sqrtGain) : 1.0f;\n                svfL.updateCoeffs(freqSmoothed, res, sr, shelfPrewarp);\n                svfR.updateCoeffs(freqSmoothed, res, sr, shelfPrewarp);')

# Restore explicit rates/diffusion while retaining upstream macro mapping at defaults.
src=src.replace('float movRate = 0.001f * fastPow2(movement * fastLog2(2000.0f));', 'float movRate = 0.001f * fastPow2(movement * fastLog2(2000.0f)) * params.driftRate / 0.07f;')
src=src.replace('float movDiff = 0.05f + movement * 0.75f;', 'float movDiff = std::clamp((0.05f + movement * 0.75f) * params.driftDiffusion / 0.4f, 0.0f, 1.0f);')
src=src.replace('rate = 0.01f * fastPow2(movement * fastLog2(800.0f));', 'rate = 0.01f * fastPow2(movement * fastLog2(800.0f)) * params.tideRate / 0.5f;')
src=src.replace('size = 0.1f + space * 0.9f;', 'size = std::clamp((0.1f + space * 0.9f) * params.reefSize / 0.5f, 0.0f, 1.0f);')
src=src.replace('decay = 0.1f + space * 0.7f;', 'decay = std::clamp((0.1f + space * 0.7f) * params.reefDecay / 0.4f, 0.0f, 0.99f);')
# First-order Ornstein-Uhlenbeck recurrence has rate-defined time constant,
# variance-normalised innovations, and sample-rate-independent damping.
src=src.replace('uint32_t rngState = 0xDEADBEEFu;', 'uint32_t initialSeed = 0xDEADBEEFu;\n        uint32_t rngState = 0xDEADBEEFu;')
pos=src.index('    struct DriftStage')
end=src.index('    } drift;',pos)
drift=src[pos:end]
drift=drift.replace('            sr = sampleRate;', '            sr = sampleRate;\n            rngState = initialSeed;',1)
drift=drift.replace('        void reset()\n        {', '        void reset()\n        {\n            rngState = initialSeed;',1)
drift=drift.replace('constexpr float theta = 2.0f;\n            float sigma = 0.3f * diffusion;\n            float dt = 1.0f / srF;\n            float sqrtDt = std::sqrt(dt);', 'float retention = std::exp(-2.0f * 3.14159265f * std::clamp(rate, 0.001f, 2.0f) / srF);\n            float innovation = 0.3f * diffusion * std::sqrt(3.0f * (1.0f - retention * retention));')
drift=drift.replace('float noise = nextRand() * sigma * sqrtDt;\n                    vx.ouV += (-theta * vx.ouX * dt + noise);\n                    vx.ouV *= 0.999f; // velocity damping\n                    vx.ouX += vx.ouV;', 'vx.ouX = retention * vx.ouX + innovation * nextRand();')
src=src[:pos]+drift+src[end:]
# Right phase is derived per sample so stereo modulation does not depend on block size.
src=src.replace('        tide.lfoPhaseR = tide.lfoPhase + stereoPhaseRad;', '        // Stereo offset is resolved sample by sample inside Tide.')
src=src.replace('float tmpPhaseR = lfoPhaseR;', 'float tmpPhaseR = std::fmod(lfoPhase + stereoPhaseRad, 2.0f * 3.14159265f);')
# Genuine deterministic sample-and-hold target instead of sine of an almost repeated phase.
src=src.replace('        float shSlew = 0.0f;', '        float shSlew = 0.0f;\n        uint32_t initialSeed = 0x12345678u, rngState = 0x12345678u;',1)
tidestart=src.index('    struct TideStage')
tideend=src.index('    } tide;',tidestart)
tide=src[tidestart:tideend]
tide=tide.replace('            sr = sampleRate;', '            sr = sampleRate;\n            rngState = initialSeed;',1)
tide=tide.replace('        void reset()\n        {', '        void reset()\n        {\n            rngState = initialSeed;',1)
tide=tide.replace('shSt = fastSin(phase + shTim * 0.1f);', 'rngState ^= rngState << 13; rngState ^= rngState >> 17; rngState ^= rngState << 5;\n                    shSt = static_cast<float>(static_cast<int32_t>(rngState)) / 2147483648.0f;')
# R S&H shares target and smoothing; it must not advance the PRNG twice at boundaries.
tide=tide.replace('float lfoR = computeLFOSample(tmpPhaseR, shape, dummyShState, dummyShSlew, dummyShTimer,\n                                              srF / effectiveRate, slewSamples);', 'float lfoR = (shape == Shape::SampleAndHold) ? lfoL : computeLFOSample(tmpPhaseR, shape, dummyShState, dummyShSlew, dummyShTimer,\n                                              srF / effectiveRate, slewSamples);')
src=src[:tidestart]+tide+src[tideend:]
# Avoid retained/replayed buffer contents when the Biolume detector closes:
# drive the input with zero but keep ring/allpass clock running until flushed.
src=src.replace('                if (gateL || gateR)\n                {', '                { // Clock the feed-forward shimmer continuously, including closed gate.')
src=src.replace('float rectL = absL * driveGain;', 'float rectL = gateL ? absL * driveGain : 0.0f;')
src=src.replace('float rectR = absR * driveGain;', 'float rectR = gateR ? absR * driveGain : 0.0f;')
src=src.replace('// Scale feedback by intensity (max 0.7 to prevent runaway)', '// Feed-forward shimmer level (upstream has no pitch-shift feedback connection).')

# The upstream computes an allpass output but returns a different non-unit-gain expression.
src=src.replace('                    float apPrev = apStateR;\n', '')
src=src.replace('                    shimR = apG * shimR + apPrev;', '                    shimR = apStateR;')

src=src.replace('            while (x > ceiling)\n                x = 2.0f * ceiling - x;\n            while (x < -ceiling)\n                x = -2.0f * ceiling - x;\n            return x;', '            const float period = 4.0f * ceiling;\n            float t = std::fmod(x + ceiling, period);\n            if (t < 0.0f) t += period;\n            return t <= 2.0f * ceiling ? t - ceiling : 3.0f * ceiling - t;')
# Prepare fully resets (including Surface smoothing state) and validates lifecycle.
src=src.replace('    sr = sampleRate;\n    blockSize = samplesPerBlock;', '    if (!std::isfinite(sampleRate) || sampleRate < 8000 || sampleRate > 192000 || samplesPerBlock < 1)\n        throw std::invalid_argument("Invalid sample rate or block size");\n    sr = sampleRate;\n    blockSize = samplesPerBlock;')
src=src.replace('    biolume.prepare(sampleRate);\n}', '    biolume.prepare(sampleRate);\n    reset();\n}',1)
src=src.replace('inline void AquaticFXSuite::processBlock(float* L, float* R, int numSamples, double bpm)\n{', 'inline void AquaticFXSuite::processBlock(float* L, float* R, int numSamples, double bpm)\n{\n    if (sr <= 0 || numSamples < 0 || !L || !R) throw std::logic_error("Prepare processor and supply valid channels first");')
(root/'src/DSP/Effects/AquaticFXSuite.h').write_text(src)
# Linear-envelope detector corrects comparing positive abs(dB) to negative dB state.
comp=(root/'upstream/Source/DSP/Effects/MultibandCompressor.h').read_text()
comp=comp.replace('float detectDb = (detect > 1e-10f) ? 6.0205999f * fastLog2(detect) : -100.0f;', '')
comp=comp.replace('float target = std::fabs(detectDb);\n                float coeff = (target > envState[b]) ? attackCoeff : releaseCoeff;\n                envState[b] = flushDenormal(envState[b] + coeff * (detectDb - envState[b]));\n                float envDb = envState[b];', 'float coeff = (detect > envState[b]) ? attackCoeff : releaseCoeff;\n                envState[b] = flushDenormal(envState[b] + coeff * (detect - envState[b]));\n                float envDb = gainToDb(envState[b]);')
comp=comp.replace('lpCoeffHigh * (midHighL - lpState[1][2])', 'lpCoeffHigh * (midHighR - lpState[1][2])')
(root/'src/DSP/Effects/MultibandCompressor.h').write_text(comp)
# Standard exp/expm1 envelope math for an offline prototype; preserve near-unity accuracy.
math=(root/'upstream/Source/DSP/FastMath.h').read_text()
start=math.index('inline float fastExp(float x)')
end=math.index('//------------------------------------------------------------------------------',start)
math=math[:start]+'inline float fastExp(float x)\n{\n    return std::exp(std::clamp(x, -87.0f, 88.0f));\n}\n\n'+math[end:]
math=math.replace('#include <cstring>','#include <cstring>\n#include <algorithm>')
start=math.index('inline float fastPow2(float x)')
end=math.index('//------------------------------------------------------------------------------',start)
math=math[:start]+'inline float fastPow2(float x)\n{\n    return std::exp2(std::clamp(x, -126.0f, 127.0f));\n}\n\n'+math[end:]
math=math.replace('return fastExp(db * 0.11512925f);','return std::exp2(db / 6.0205999f);')
(root/'src/DSP/FastMath.h').write_text(math)
# Record canonical parameter ranges for callers and docs.
import json
(root/'parameters.json').write_text(json.dumps({key:{'min':lo,'max':hi,'default':default} for key,(lo,hi,default) in metadata.items()},indent=2)+'\n')
print('Extracted',len(ids),'parameters')
