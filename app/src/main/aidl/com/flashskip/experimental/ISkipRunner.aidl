package com.flashskip.experimental;

import com.flashskip.experimental.ISkipHost;

interface ISkipRunner {
    oneway void configure(ISkipHost host, boolean masterEnabled, boolean observeOnly,
        long windowMs, in String[] disabledPackages) = 0;
    oneway void destroy() = 16777114;
}
