#ifndef HARMON_TEST_ANCHORS_H
#define HARMON_TEST_ANCHORS_H


#include <stddef.h>
#include <stdint.h>

static inline uint64_t hm_lowest(uint64_t first, uint64_t second) {
    return first < second ? first : second;
}

static inline uint64_t hm_highest(uint64_t first, uint64_t second) {
    return first > second ? first : second;
}

static inline uint64_t hm_absolute_difference(uint64_t first, uint64_t second) {
    return first > second ? first - second : second - first;
}

static inline int hm_lowest_int(int first, int second) {
    return first < second ? first : second;
}

static inline int hm_highest_int(int first, int second) {
    return first > second ? first : second;
}

static inline uint64_t hm_below(uint64_t value, uint64_t slack) {
    return value > slack ? value - slack : 0;
}

typedef struct {
    const char *name;
    uint64_t reported;
    uint64_t anchor;
    uint64_t tolerance;
} HMAnchoredField;

static inline const char *hm_first_mismatch(
    const HMAnchoredField *fields,
    size_t count,
    uint64_t *reported,
    uint64_t *anchor
) {
    for (size_t index = 0; index < count; ++index) {
        if (hm_absolute_difference(fields[index].reported, fields[index].anchor) <=
            fields[index].tolerance) {
            continue;
        }
        *reported = fields[index].reported;
        *anchor = fields[index].anchor;
        return fields[index].name;
    }
    return NULL;
}

typedef struct {
    const char *name;
    uint64_t reported;
    uint64_t low;
    uint64_t high;
} HMBracketedField;

static inline const char *hm_first_outside_range(
    const HMBracketedField *fields,
    size_t count,
    uint64_t *reported,
    uint64_t *low,
    uint64_t *high
) {
    for (size_t index = 0; index < count; ++index) {
        if (fields[index].reported >= fields[index].low &&
            fields[index].reported <= fields[index].high) {
            continue;
        }
        *reported = fields[index].reported;
        *low = fields[index].low;
        *high = fields[index].high;
        return fields[index].name;
    }
    return NULL;
}

#define HM_FIRST_MISMATCH(fields, reported, anchor) \
    hm_first_mismatch((fields), sizeof(fields) / sizeof((fields)[0]), (reported), (anchor))

#define HM_FIRST_OUTSIDE_RANGE(fields, reported, low, high)          \
    hm_first_outside_range(                                          \
        (fields),                                                    \
        sizeof(fields) / sizeof((fields)[0]),                        \
        (reported),                                                  \
        (low),                                                       \
        (high)                                                       \
    )

#endif
