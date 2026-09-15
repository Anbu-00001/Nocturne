#!/usr/bin/env Rscript
# Reference values from nparACT (Blume, Santhi and Schabus, MethodsX 2016) for Nocturne's rest-activity metrics.
#
#   Rscript nparact_golden.R minutes.txt           one value per minute from 12:00, '#' lines skipped; prints nparACT's result
#   Rscript nparact_golden.R --synthetic <dir>     writes the synthetic weeks and nparACT's values for NparactGoldenTest
#
# What nparACT 0.9.1 does, read from its source:
# - IS = n * sum over the 24 hours of (hourly mean - mean)^2 / (24 * sum of (x - mean)^2), on hourly means from the
#   first sample; IV = n * sum of squared hour-to-hour differences / ((n - 1) * sum of (x - mean)^2). Van Someren's.
# - L5 and M10 are means over 300 and 600 minutes of the minute-wise average day, laid out twice so windows wrap. The
#   start is the first minimum or maximum counted from the first sample.
# - IS, IV, L5 and M10 are rounded to 2 decimals, and RA is computed from the rounded L5 and M10.
# - Its activity cutoff never takes effect (the filter edits a copy of the data); cutoff = 0 is passed anyway.
# - Missing values stop it, so only gapless weeks can be compared.
suppressPackageStartupMessages(library(nparACT))
# The values above were read from 0.9.1; another version may compute differently.
stopifnot(packageVersion("nparACT") == "0.9.1")

run_nparact <- function(values, start = "2026-09-07 12:00:00") {
  stopifnot(length(values) %% 1440 == 0, !anyNA(values))
  minutes_df <- data.frame(
    time = seq(as.POSIXct(start, tz = "UTC"), by = 60, length.out = length(values)),
    activity = values
  )
  # nparACT_base looks its data up by name.
  assign("minutes_df", minutes_df, envir = globalenv())
  nparACT_base("minutes_df", SR = 1 / 60, cutoff = 0, plot = FALSE, fulldays = TRUE)
}

read_minutes <- function(path) {
  lines <- readLines(path)
  as.numeric(lines[!startsWith(lines, "#")])
}

synthetic <- function(dir) {
  dir.create(dir, showWarnings = FALSE, recursive = TRUE)
  set.seed(20260915)
  clock <- (12 * 60 + 0:(7 * 1440 - 1)) %% 1440 # minutes after midnight; the week starts at 12:00
  weeks <- list(
    # Busy by day, quiet around 03:00, with minute-to-minute noise.
    rhythm = pmin(1, pmax(0, 0.45 + 0.4 * cos(2 * pi * (clock - 15 * 60) / 1440) + rnorm(length(clock), sd = 0.15))),
    # No rhythm at all.
    noise = runif(length(clock))
  )
  for (name in names(weeks)) {
    input <- file.path(dir, paste0(name, "-week.txt"))
    writeLines(sprintf("%.2f", weeks[[name]]), input)
    result <- run_nparact(read_minutes(input))
    writeLines(sprintf("%s=%s", names(result), sapply(result[1, ], as.character)), file.path(dir, paste0(name, "-week-nparact.txt")))
  }
}

args <- commandArgs(trailingOnly = TRUE)
if (length(args) == 2 && args[1] == "--synthetic") {
  synthetic(args[2])
} else if (length(args) == 1) {
  result <- run_nparact(read_minutes(args[1]))
  writeLines(sprintf("%s=%s", names(result), sapply(result[1, ], as.character)))
} else {
  stop("usage: nparact_golden.R minutes.txt | nparact_golden.R --synthetic <dir>")
}
