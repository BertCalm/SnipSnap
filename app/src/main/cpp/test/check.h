// A test harness small enough to read in one breath: CHECK records a
// failure with its line and carries on, so one run reports every broken
// assertion rather than the first.
#pragma once

#include <cmath>
#include <cstdio>
#include <functional>
#include <string>
#include <utility>
#include <vector>

namespace check {

struct Case {
    const char* name;
    std::function<void()> body;
};

inline std::vector<Case>& cases() {
    static std::vector<Case> all;
    return all;
}

inline int& failures() {
    static int n = 0;
    return n;
}

struct Register {
    Register(const char* name, std::function<void()> body) { cases().push_back({name, std::move(body)}); }
};

inline void fail(const char* expr, const char* file, int line, const std::string& detail) {
    ++failures();
    std::printf("  FAIL %s:%d: %s%s\n", file, line, expr, detail.empty() ? "" : (" - " + detail).c_str());
}

inline int runAll() {
    int failed = 0;
    for (const auto& c : cases()) {
        const int before = failures();
        std::printf("- %s\n", c.name);
        c.body();
        if (failures() != before) ++failed;
    }
    std::printf("%zu cases, %d failed\n", cases().size(), failed);
    return failed == 0 ? 0 : 1;
}

}  // namespace check

#define TEST(name) \
    static void name##_body(); \
    static check::Register name##_reg(#name, name##_body); \
    static void name##_body()

#define CHECK(expr) \
    do { if (!(expr)) check::fail(#expr, __FILE__, __LINE__, ""); } while (0)

// Each operand is evaluated exactly once, so an expression with a side
// effect (an `i++`, a pop) reads the same in the check and in the message.
#define CHECK_EQ(a, b) \
    do { \
        const auto checkA_ = (a); \
        const auto checkB_ = (b); \
        if (!(checkA_ == checkB_)) check::fail(#a " == " #b, __FILE__, __LINE__, "got " + std::to_string(checkA_) + " vs " + std::to_string(checkB_)); \
    } while (0)

#define CHECK_NEAR(a, b, eps) \
    do { \
        const double checkA_ = static_cast<double>(a); \
        const double checkB_ = static_cast<double>(b); \
        if (std::fabs(checkA_ - checkB_) > (eps)) check::fail(#a " ~ " #b, __FILE__, __LINE__, "got " + std::to_string(checkA_) + " vs " + std::to_string(checkB_)); \
    } while (0)
