// SPDX-License-Identifier: MIT
// Audition presets by OpenAI, based on XO_OX Aquarium controls.
#pragma once
#include "src/DSP/Effects/AquaticFXSuite.h"
#include <map>
#include <string>
#include <vector>
namespace audition {
using SettingMap = std::map<std::string, float>;
inline const std::vector<std::string>& presetNames() {
    static const std::vector<std::string> names={"dry","fathom","drift","tide","reef","surface","biolume","depth","motion","color","full"};
    return names;
}
inline SettingMap presetSettings(const std::string& name) {
    if(std::find(presetNames().begin(),presetNames().end(),name)==presetNames().end()) throw std::invalid_argument("Unknown preset: "+name);
    SettingMap out;
    auto enable = [&](const std::string& effect, const SettingMap& settings) {
        if(name==effect || name=="full" || (name=="depth"&&(effect=="fathom"||effect=="surface")) || (name=="motion"&&(effect=="drift"||effect=="tide")) || (name=="color"&&(effect=="reef"||effect=="biolume"))) out.insert(settings.begin(),settings.end());
    };
    enable("fathom", {{"aqua_fathom_mix",.40f},{"aqua_fathom_depth",.28f},{"aqua_fathom_character",.25f}});
    enable("drift", {{"aqua_drift_mix",.25f},{"aqua_drift_depth",5.f},{"aqua_drift_voices",3.f},{"aqua_drift_movement",.62f},{"aqua_drift_rate",.09f},{"aqua_drift_feedback",.12f},{"aqua_drift_diffusion",.55f},{"aqua_drift_stereoSpread",.9f},{"aqua_drift_space",.8f}});
    enable("tide", {{"aqua_tide_mix",.55f},{"aqua_tide_depth",.38f},{"aqua_tide_mode",2.f},{"aqua_tide_filterFreq",3200.f},{"aqua_tide_filterRes",.16f},{"aqua_tide_syncEnabled",1.f},{"aqua_tide_syncDiv",4.f},{"aqua_tide_stereoPhase",60.f}});
    enable("reef", {{"aqua_reef_mix",.24f},{"aqua_reef_space",.55f},{"aqua_reef_predelay",9.f},{"aqua_reef_density",.6f},{"aqua_reef_damping",.65f},{"aqua_reef_width",.85f}});
    enable("surface", {{"aqua_surface_mix",.8f},{"aqua_surface_gain",-4.5f},{"aqua_surface_freq",3200.f},{"aqua_surface_resonance",.1f},{"aqua_surface_mode",0.f},{"aqua_surface_sweep",.5f},{"aqua_surface_coupling",0.f}});
    enable("biolume", {{"aqua_biolume_mix",.28f},{"aqua_biolume_drive",.33f},{"aqua_biolume_hpFreq",2000.f},{"aqua_biolume_threshold",-36.f},{"aqua_biolume_shimmer",.45f},{"aqua_biolume_character",.65f},{"aqua_biolume_spread",.5f}});
    if(name=="full") {out["aqua_fathom_mix"]=.32f;out["aqua_drift_mix"]=.18f;out["aqua_tide_mix"]=.35f;out["aqua_reef_mix"]=.18f;out["aqua_surface_mix"]=.7f;out["aqua_biolume_mix"]=.18f;}
    return out;
}
inline void applyPreset(accepted_audition::AquaticFXSuite& core, const std::string& name) {
    for(const auto& [id, value]:presetSettings(name)) core.setParameter(id,value);
}
}
