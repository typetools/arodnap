# Return Cycle

`Cycle.open` leaks its stream on one path and returns the result of calling
itself on the other. RLFixer fixes a returned resource in the callers, and here
the caller is the method itself, so without a guard it recursed until the stack
overflowed. The leak must be reported as unfixable while `FirstByte`, an
ordinary leak, is still repaired.
