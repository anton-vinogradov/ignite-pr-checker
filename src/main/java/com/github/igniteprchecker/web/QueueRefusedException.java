package com.github.igniteprchecker.web;

import com.github.igniteprchecker.tc.TcResponseException;

/**
 * TeamCity answered 403 when asked to queue a build of the PR: the user's ci2 account may lack the right to run builds,
 * though ci2's firewall refuses some good requests with the same 403.
 */
class QueueRefusedException extends TcResponseException {
    QueueRefusedException(TcResponseException e) {
        super(e);
    }
}
