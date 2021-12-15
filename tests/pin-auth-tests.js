/**
 * Copyright (c) 2017, Oracle and/or its affiliates.
 * The Universal Permissive License (UPL), Version 1.0
 */
/* jshint esversion: 6 */
exports.defineAutoTests = function() {
  var idmAuthFlowPlugin = cordova.plugins.IdmAuthFlows;
  var pinChallengeReason = idmAuthFlowPlugin.LocalAuthPropertiesBuilder.PinChallengeReason;
  var localAuthTypes = idmAuthFlowPlugin.LocalAuthPropertiesBuilder.LocalAuthenticatorType;
  var pinAuthFlow, isCancelFlow, currPin, newPin, challengeReasons, challengeErrors, challengeCount;
  var enabledStates, loginResults, disableSuccess, storedKey, storedValue;
  var setSuccessResults, getSuccessResults;

  var resetTest = function() {
    challengeReasons = [];
    challengeErrors = [];
    challengeCount = [];
    enabledStates = [];
    setSuccessResults = []; 
    getSuccessResults = [];
    isCancelFlow = undefined;
    currPin = undefined;
    newPin = undefined;
    loginResults = [];
    disableSuccess = false;
    storedKey = undefined;
    storedValue = undefined;
  };

  var pinChallenge = function (challengeReason, completionHandler, err, options) {
    challengeReasons.push(challengeReason);

    if (err)
      challengeErrors.push(err);

    if (options && options.loginAttemptCount)
      challengeCount.push(options.loginAttemptCount);

    if (isCancelFlow) {
      completionHandler.cancel();
      return;
    }

    completionHandler.submit(currPin, newPin);
  };

  var pinAuthProps = new idmAuthFlowPlugin.LocalAuthPropertiesBuilder()
                            .id("testPinAuth")
                            .pinChallengeCallback(pinChallenge)
                            .maxLoginAttemptsForPIN(3)
                            .build();

  var init = function(done) {
    idmAuthFlowPlugin.init(pinAuthProps)
      .then(function(flow) {
        pinAuthFlow = flow;
        done();
      })
      .catch(done);
  };

  describe('Test PIN based local authentication', function () {
    beforeAll(function(done) {
      defaultJasmineTimeout = jasmine.DEFAULT_TIMEOUT_INTERVAL;
      jasmine.DEFAULT_TIMEOUT_INTERVAL = 10000;
      init(done);
    });
    afterAll(function() {
      jasmine.DEFAULT_TIMEOUT_INTERVAL = defaultJasmineTimeout;
    });

    describe('check API', function() {
      it('has all expected methods', function(done) {
        expect(pinAuthFlow).toBeDefined();
        expect(pinAuthFlow.login).toBeDefined();
        expect(pinAuthFlow.logout).toBeDefined();
        expect(pinAuthFlow.isAuthenticated).toBeDefined();
        expect(pinAuthFlow.getManager).toBeDefined();
        expect(pinAuthFlow.getManager()).toBeDefined();
        expect(pinAuthFlow.getManager().enable).toBeDefined();
        expect(pinAuthFlow.getManager().disable).toBeDefined();
        expect(pinAuthFlow.getManager().changePin).toBeDefined();
        expect(pinAuthFlow.getManager().getEnabled).toBeDefined();
        done();
      });
    });

    describe('enable without proper local auth type.', function() {
      var enableErr;
      beforeEach(function(done) {
        resetTest();
        pinAuthFlow.getManager().enable()
          .catch(function(er) {
            enableErr = er;
          })
          .then(done)
          .catch(done);
      });
      it('throws expected error code.', function(done) {
        window.TestUtil.verifyPluginError(enableErr, "P1014");
        done();
      });
    });

    describe('enable fingerprint when PIN is not.', function() {
      var enableErr;
      beforeEach(function(done) {
        resetTest();
        pinAuthFlow.getManager().enable(localAuthTypes.Fingerprint)
          .catch(function(er) {
            enableErr = er;
          })
          .then(done)
          .catch(done);
      });
      it('throws expected error code.', function(done) {
        window.TestUtil.verifyPluginError(enableErr, "P1016");
        done();
      });
    });

    describe('enable and cancel the challenge', function() {
      var enableErr;
      beforeEach(function(done) {
        resetTest();
        isCancelFlow = true;
        currPin = undefined;
        newPin = "1234";

        pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          .catch(function(er) {
            enableErr = er;
          })
          .then(done)
          .catch(done);
      });
      it('throws expected error code.', function(done) {
        window.TestUtil.verifyPluginError(enableErr, "10029");
        expect(challengeReasons.length).toBe(1);
        expect(challengeReasons[0]).toBe(pinChallengeReason.SetPin);
        done();
      });
    });

    describe('disable invalid local auth type.', function() {
      var disableErr;
      beforeEach(function(done) {
        resetTest();
        pinAuthFlow.getManager().disable()
          .catch(function(er) {
            disableErr = er;
          })
          .then(done)
          .catch(done);
      });
      it('throws expected error code.', function(done) {
        window.TestUtil.verifyPluginError(disableErr, "P1014");
        done();
      });
    });

    describe('login without enabling.', function() {
      beforeEach(function(done) {
        resetTest();
        pinAuthFlow.getManager().getEnabled()
          .then(function(enabled) {
            enabledStates.push(enabled);
          })
          .then(function() {
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .catch(function(er) {
            loginResults.push(er);
          })
          .then(done)
          .catch(done);
      });
      it('throws expected error code.', function(done) {
        expect(enabledStates.length).toBe(1);
        expect(enabledStates[0].length).toBe(0);
        expect(challengeReasons.length).toBe(0);
        expect(loginResults.length).toBe(1);
        window.TestUtil.verifyPluginError(loginResults[0], "P1013");
        done();
      });
    });

    // We can test isAuthenticated for false only once.
    // User once logged in is always logged in for that instance of the app.
    describe('enable and then disable with implicit login.', function() {
      var isAuthenticatedStates = [];
      beforeEach(function(done) {
        resetTest();
        pinAuthFlow.getManager().getEnabled()
          .then(function(enabled) {
            enabledStates.push(enabled);
          })
          .then(function() {
            currPin = undefined;
            newPin = "1234";
            return pinAuthFlow.getManager().enable(localAuthTypes.PIN);
          })
          .then(function() {
            return pinAuthFlow.isAuthenticated();
          })
          .then(function(isAuth) {
            isAuthenticatedStates.push(isAuth)
            return pinAuthFlow.getManager().getEnabled();
          })
          .then(function(enabled) {
            enabledStates.push(enabled);
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            disableSuccess = true;
            return pinAuthFlow.isAuthenticated();
          })
          .then(function(isAuth) {
            isAuthenticatedStates.push(isAuth)
            return pinAuthFlow.getManager().getEnabled();
          })
          .then(function(enabled) {
            enabledStates.push(enabled);
          })
          .then(done)
          .catch(done);
      });
      it('works as expected.', function(done) {
        expect(disableSuccess).toBeTruthy();
        expect(enabledStates.length).toBe(3);
        expect(enabledStates[0].length).toBe(0);
        expect(enabledStates[1].length).toBe(1);
        expect(enabledStates[1][0]).toBe(localAuthTypes.PIN);
        expect(enabledStates[2].length).toBe(0);

        expect(challengeReasons.length).toBe(2);
        expect(challengeReasons[0]).toBe(pinChallengeReason.SetPin);
        expect(challengeReasons[1]).toBe(pinChallengeReason.Login);

        expect(isAuthenticatedStates.length).toBe(2);
        expect(isAuthenticatedStates[0]).not.toBeTruthy();
        expect(isAuthenticatedStates[1]).toBeTruthy();

        done();
      });
    });

    describe('enable and enable again, authenticate and disable', function() {
      beforeEach(function(done) {
        resetTest();
        pinAuthFlow.getManager().getEnabled()
          .then(function(enabled) {
            enabledStates.push(enabled);
          })
          .then(function() {
            currPin = undefined;
            newPin = "1234";
            return pinAuthFlow.getManager().enable(localAuthTypes.PIN);
          })
          .then(function() {
            return pinAuthFlow.getManager().getEnabled();
          })
          .then(function(enabled) {
            enabledStates.push(enabled);
            currPin = undefined;
            newPin = "1234";
            return pinAuthFlow.getManager().enable(localAuthTypes.PIN);
          })
          .then(function() {
            return pinAuthFlow.getManager().getEnabled();
          })
          .then(function(enabled) {
            enabledStates.push(enabled);
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function(flow) {
            loginResults.push(true);
            return flow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            disableSuccess = true;
            return pinAuthFlow.getManager().getEnabled();
          })
          .then(function(enabled) {
            enabledStates.push(enabled);
          })
          .then(done)
          .catch(done);
      });
      it('works as expected.', function(done) {
        expect(disableSuccess).toBeTruthy();
        expect(enabledStates.length).toBe(4);
        expect(enabledStates[0].length).toBe(0);
        expect(enabledStates[1].length).toBe(1);
        expect(enabledStates[1][0]).toBe(localAuthTypes.PIN);
        expect(enabledStates[2].length).toBe(1);
        expect(enabledStates[2][0]).toBe(localAuthTypes.PIN);
        expect(enabledStates[3].length).toBe(0);

        expect(loginResults.length).toBe(1);
        expect(loginResults[0]).toBeTruthy();

        expect(challengeReasons.length).toBe(3);
        expect(challengeReasons[0]).toBe(pinChallengeReason.SetPin);
        expect(challengeReasons[1]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[2]).toBe(pinChallengeReason.Login);
        done();
      });
    });

    describe('disable and authenticate then enable and authenticate and disable', function() {
      beforeEach(function(done) {
        resetTest();
        pinAuthFlow.getManager().getEnabled()
          .then(function(enabled) {
            enabledStates.push(enabled);
          })
          .then(function() {
            return pinAuthFlow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            return pinAuthFlow.getManager().getEnabled();
          })
          .then(function(enabled) {
            enabledStates.push(enabled);
          })
          .then(function() {
            return pinAuthFlow.login();
          })
          .catch(function(er) {
            loginResults.push(er);
            currPin = undefined;
            newPin = "1234";
            return pinAuthFlow.getManager().enable(localAuthTypes.PIN);
          })
          .then(function() {
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function(flow) {
            loginResults.push(true);
            return flow.getManager().getEnabled();
          })
          .then(function(enabled) {
            enabledStates.push(enabled);
            return pinAuthFlow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            disableSuccess = true;
            return pinAuthFlow.getManager().getEnabled();
          })
          .then(function(enabled) {
            enabledStates.push(enabled);
          })
          .then(done)
          .catch(done);
      });
      it('works as expected.', function(done) {
        expect(disableSuccess).toBeTruthy();
        expect(enabledStates.length).toBe(4);
        expect(enabledStates[0].length).toBe(0);
        expect(enabledStates[1].length).toBe(0);
        expect(enabledStates[2].length).toBe(1);
        expect(enabledStates[2][0]).toBe(localAuthTypes.PIN);
        expect(enabledStates[3].length).toBe(0);

        expect(challengeReasons.length).toBe(3);
        expect(challengeReasons[0]).toBe(pinChallengeReason.SetPin);
        expect(challengeReasons[1]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[2]).toBe(pinChallengeReason.Login);

        expect(loginResults.length).toBe(2);
        window.TestUtil.verifyPluginError(loginResults[0], "P1013");
        expect(loginResults[1]).toBeTruthy();

        done();
      });
    });

    describe('enable, login, change pin and login. Again change pin and login and disable', function() {
      beforeEach(function(done) {
        resetTest();

        currPin = undefined;
        newPin = "1234";
        pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          .then(function() {
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function() {
            currPin = "1234";
            newPin = "2345";
            return pinAuthFlow.getManager().changePin();
          })
          .then(function() {
            currPin = "2345";
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function(flow) {
            loginResults.push(true);
            currPin = "2345";
            newPin = "1234";
            return flow.getManager().changePin();
          })
          .then(function() {
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function(flow) {
            loginResults.push(true);
            return flow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            disableSuccess = true;
            done();
          })
          .catch(done);
      });
      it('works as expected.', function(done) {
        expect(disableSuccess).toBeTruthy();
        expect(loginResults.length).toBe(2);
        expect(loginResults[0]).toBeTruthy();
        expect(loginResults[1]).toBeTruthy();

        expect(challengeReasons.length).toBe(9);
        expect(challengeReasons[0]).toBe(pinChallengeReason.SetPin);
        expect(challengeReasons[1]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[2]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[3]).toBe(pinChallengeReason.ChangePin);
        expect(challengeReasons[4]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[5]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[6]).toBe(pinChallengeReason.ChangePin);
        expect(challengeReasons[7]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[8]).toBe(pinChallengeReason.Login);
        done();
      });
    });

    describe('enable, login and cancel the challenge, login and disable', function() {
      var loginErr;
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";

        pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          .then(function() {
            currPin = "1234";
            newPin = undefined;
            isCancelFlow = true;
            return pinAuthFlow.login();
          })
          .catch(function(er) {
            loginErr = er;
            currPin = "1234";
            newPin = undefined;
            isCancelFlow = false;
            return pinAuthFlow.login();
          })
          .then(function() {
            return pinAuthFlow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            disableSuccess = true;
            done();
          })
          .catch(done);
      });
      it('throws expected error code.', function(done) {
        expect(disableSuccess).toBeTruthy();
        window.TestUtil.verifyPluginError(loginErr, "10029");
        expect(challengeReasons.length).toBe(4);
        expect(challengeReasons[0]).toBe(pinChallengeReason.SetPin);
        expect(challengeReasons[1]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[2]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[3]).toBe(pinChallengeReason.Login);
        done();
      });
    });

    describe('enable, login with wrong PIN, login with correct PIN, disable, with maxRetry check.', function() {
      var loginErr;
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";
        pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          .then(function() {
            currPin = "2222";  // Wrong PIN
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .catch(function(er) {
            loginErr = er;
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function() {
            return pinAuthFlow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            disableSuccess = true;
            done();
          })
          .catch(done);
      });
      it('throws expected error code.', function(done) {
        expect(disableSuccess).toBeTruthy();
        window.TestUtil.verifyPluginError(loginErr, "10408");
        expect(challengeReasons.length).toBe(6);
        expect(challengeReasons[0]).toBe(pinChallengeReason.SetPin);
        expect(challengeReasons[1]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[2]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[3]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[4]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[5]).toBe(pinChallengeReason.Login);

        expect(challengeErrors.length).toBe(2);
        window.TestUtil.verifyPluginError(challengeErrors[0], "10408");
        window.TestUtil.verifyPluginError(challengeErrors[1], "10408");
        expect(challengeCount.length).toBe(5);
        expect(challengeCount[0]).toBe(1);
        expect(challengeCount[1]).toBe(2);
        expect(challengeCount[2]).toBe(3);
        expect(challengeCount[3]).toBe(1);
        expect(challengeCount[4]).toBe(1);

        done();
      });
    });

    describe('enable, login, change pin and cancel the challenge', function() {
      var changePinErr;
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";

        pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          .then(function() {
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function() {
            currPin = "1234";
            newPin = "2345";
            isCancelFlow = true;
            return pinAuthFlow.getManager().changePin();
          })
          .catch(function(er) {
            changePinErr = er;
            currPin = "1234";
            newPin = undefined;
            isCancelFlow = false;
            return pinAuthFlow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            disableSuccess = true;
            done();
          })
          .catch(done);
      });
      it('throws expected error code.', function(done) {
        expect(disableSuccess).toBeTruthy();
        window.TestUtil.verifyPluginError(changePinErr, "10029");
        expect(challengeReasons.length).toBe(4);
        expect(challengeReasons[0]).toBe(pinChallengeReason.SetPin);
        expect(challengeReasons[1]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[2]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[3]).toBe(pinChallengeReason.Login);
        done();
      });
    });

    describe('enable, login with correct PIN, change PIN with wrong PIN, login with old PIN, disable', function() {
      var pinChangeErr;
      isCancelFlow = false;
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";

        pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          .then(function() {
            currPin = "1234"; // Login with correct PIN.
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function() {
            currPin = "1111"; // Wrong current PIN
            newPin = "2345";
            return pinAuthFlow.getManager().changePin();
          })
          .catch(function(err) {
            pinChangeErr = err;
            currPin = "1234"; // Correct PIN
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function() {
            return pinAuthFlow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            disableSuccess = true;
            done();
          })
          .catch(done);
      });
      it('throws expected error code.', function(done) {
        expect(disableSuccess).toBeTruthy();
        window.TestUtil.verifyPluginError(pinChangeErr, "10408");
        expect(challengeReasons.length).toBe(7);
        expect(challengeReasons[0]).toBe(pinChallengeReason.SetPin);
        expect(challengeReasons[1]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[2]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[3]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[4]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[5]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[6]).toBe(pinChallengeReason.Login);

        expect(challengeErrors.length).toBe(2);
        window.TestUtil.verifyPluginError(challengeErrors[0], "10408");
        window.TestUtil.verifyPluginError(challengeErrors[1], "10408");
        expect(challengeCount.length).toBe(6);
        expect(challengeCount[0]).toBe(1);
        expect(challengeCount[1]).toBe(1);
        expect(challengeCount[2]).toBe(2);
        expect(challengeCount[3]).toBe(3);
        expect(challengeCount[4]).toBe(1);
        expect(challengeCount[5]).toBe(1);

        done();
      });
    });

    describe('enable, login, disable, enable again with different PIN, login, disable', function() {
      isCancelFlow = false;
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";

        pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          .then(function() {
            currPin = "1234"; // Login with correct PIN.
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function() {
            loginResults.push(true);
            return pinAuthFlow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            currPin = undefined;
            newPin = "2345"; // Enable with different PIN
            return pinAuthFlow.getManager().enable(localAuthTypes.PIN);
          })
          .then(function() {
            currPin = "2345"; // Login with correct PIN.
            newPin = undefined;
            return pinAuthFlow.login();
          })
          .then(function() {
            loginResults.push(true);
            return pinAuthFlow.getManager().disable(localAuthTypes.PIN);
          })
          .then(function() {
            disableSuccess = true;
            done();
          })
          .catch(done);
      });
      it('works as expected.', function(done) {
        expect(disableSuccess).toBeTruthy();
        expect(challengeReasons.length).toBe(6);
        expect(challengeReasons[0]).toBe(pinChallengeReason.SetPin);
        expect(challengeReasons[1]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[2]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[3]).toBe(pinChallengeReason.SetPin);
        expect(challengeReasons[4]).toBe(pinChallengeReason.Login);
        expect(challengeReasons[5]).toBe(pinChallengeReason.Login);

        expect(loginResults.length).toBe(2);
        expect(loginResults[0]).toBeTruthy();
        expect(loginResults[1]).toBeTruthy();

        done();
      });
    });

    // Test Cases for Secure/Default Key Storage
    describe('set preference (non secure)', function() {
      beforeEach(function(done) {
        resetTest();
        storedKey = "language";
        storedValue = "german";
        pinAuthFlow.getManager().setPreference(storedKey, storedValue, false)
          .then(function() {
            setSuccessResults.push(true);
            pinAuthFlow.getManager().setPreference(storedKey, null, false); //Deletes the stored value
            done();
          }).catch(done);
      });
      it('works as expected.', function(done) {
        expect(setSuccessResults.length).toBe(1);
        expect(setSuccessResults[0]).toBeTruthy();
        done();
      });
    });

    describe('set preference (secure)', function() {
      var setPreferenceError;
      beforeEach(function(done) {
        resetTest();
        storedKey = "language";
        storedValue = "german";
        pinAuthFlow.getManager().setPreference(storedKey, storedValue, true)
          .then(function() {
            setSuccessResults.push(true);
            done();
          }).catch(function(err) {
            setPreferenceError = err;
            done();
          });
      });
      it('throws expected error code.', function(done) {
        expect(setSuccessResults.length).toBe(0);
        expect(setPreferenceError).toBeDefined();
        window.TestUtil.verifyPluginError(setPreferenceError, "P1016");
        done();
      });
    });

    describe('get preference', function() {
      var getPreferenceError, fetchedValue;
      beforeEach(function(done) {
        resetTest();
        storedKey = "language";
        storedValue = "german";
        pinAuthFlow.getManager().getPreference(storedKey)
          .then(function(value) {
            getSuccessResults.push(true);
            fetchedValue = value;
            done();
          }).catch(function(err) {
            getPreferenceError = err;
            done();
          });
      });
      it('works as expected.', function(done) {
        expect(getSuccessResults.length).toBe(1);
        expect(getSuccessResults[0]).toBeTruthy();
        expect(fetchedValue).toBeNull();
        expect(getPreferenceError).not.toBeDefined();
        done();
      });
    });

    describe('set preference (non secure), get preference', function() {
      beforeEach(function(done) {
        resetTest();
        storedKey = "language";
        storedValue = "german";
        pinAuthFlow.getManager().setPreference(storedKey, storedValue, false)
          .then(function() {
            setSuccessResults.push(true);
            return pinAuthFlow.getManager().getPreference(storedKey)
          }).then(function() {
            getSuccessResults.push(true);
            done();
          }).catch(done);
      });
      it('works as expected.', function(done) {
        expect(setSuccessResults.length).toBe(1);
        expect(setSuccessResults[0]).toBeTruthy();
        expect(getSuccessResults.length).toBe(1);
        expect(getSuccessResults[0]).toBeTruthy();
        done();
      });
    });

    describe('set preference (non secure), get preference, delete preference(non secure), get preference', function() {
      var fetchedValues = [];
      beforeEach(function(done) {
        resetTest();
        storedKey = "language";
        storedValue = "german";
        pinAuthFlow.getManager().setPreference(storedKey, storedValue, false)
          .then(function() {
            setSuccessResults.push(true);
            return pinAuthFlow.getManager().getPreference(storedKey)
          }).then(function(value) {
            getSuccessResults.push(true);
            fetchedValues.push(value);
            return pinAuthFlow.getManager().setPreference(storedKey, null, false) // Deletion
          }).then(function() {
            setSuccessResults.push(true);
            return pinAuthFlow.getManager().getPreference(storedKey)
          }).then(function(value) {
            getSuccessResults.push(true);
            fetchedValues.push(value);
            done();
          }).catch(done);
      });
      it('works as expected.', function(done) {
        expect(setSuccessResults.length).toBe(2);
        expect(setSuccessResults[0]).toBeTruthy();
        expect(setSuccessResults[1]).toBeTruthy();
        expect(getSuccessResults.length).toBe(2);
        expect(getSuccessResults[0]).toBeTruthy();
        expect(getSuccessResults[1]).toBeTruthy();
        expect(fetchedValues.length).toBe(2);
        expect(fetchedValues[0]).toBe(storedValue);
        expect(fetchedValues[1]).toBeNull();
        done();
      });
    });    

    xdescribe('enable, set preference (secure), login, set preference (secure), get preference', function() {
      var loginErr;
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";
        storedKey = "language";
        storedValue = "german";

        pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          .then(function() {
            return pinAuthFlow.getManager().setPreference(storedKey, storedValue, true)
          }).catch(function(err) {
            loginErr = err;
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login();
          }).then(function() {
            return pinAuthFlow.getManager().setPreference(storedKey, storedValue, true)
          }).then(function() {
            setSuccessResults.push(true);
            return pinAuthFlow.getManager().getPreference(storedKey)
          }).then(function() {
            getSuccessResults.push(true);
            done();
          }).catch(done);
      });
      it('works as expected.', function(done) {
        expect(setSuccessResults.length).toBe(1);
        expect(setSuccessResults[0]).toBeTruthy();
        expect(getSuccessResults.length).toBe(1);
        expect(getSuccessResults[0]).toBeTruthy();
        expect(loginErr).toBeDefined();
        done();
      });
    });

    describe('set preference (secure), enable, login, set preference (secure), get preference', function() {
      var setPreferenceError;
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";
        storedKey = "language";
        storedValue = "german";

        pinAuthFlow.getManager().setPreference(storedKey, "german", true)
          .catch(function(err) {
            setPreferenceError = err;
            setSuccessResults.push(false);
            return pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          }).then(function() {
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login()
          }).then(function() {
            return pinAuthFlow.getManager().setPreference(storedKey, storedValue, true)
          }).then(function() {
            setSuccessResults.push(true);
            return pinAuthFlow.getManager().getPreference(storedKey)
          }).then(function() {
            getSuccessResults.push(true);
            done();
          }).catch(done);
      });
      it('works as expected.', function(done) {
        expect(setSuccessResults.length).toBe(2);
        expect(setSuccessResults[0]).not.toBeTruthy();
        expect(setSuccessResults[1]).toBeTruthy();
        expect(getSuccessResults.length).toBe(1);
        expect(getSuccessResults[0]).toBeTruthy();
        expect(setPreferenceError).toBeDefined();
        window.TestUtil.verifyPluginError(setPreferenceError, "P1016");
        done();
      });
    });

    describe('set preference (non secure), enable, login, set preference (secure), get preference', function() {
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";
        storedKey = "language";
        storedValue = "german";

        pinAuthFlow.getManager().setPreference(storedKey, "german", false)
          .then(function() {
            setSuccessResults.push(true);
            return pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          }).then(function() {
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login()
          }).then(function() {
            return pinAuthFlow.getManager().setPreference(storedKey, storedValue, true)
          }).then(function() {
            setSuccessResults.push(true);
            return pinAuthFlow.getManager().getPreference(storedKey)
          }).then(function() {
            getSuccessResults.push(true);
            done();
          }).catch(done);
      });
      it('works as expected.', function(done) {
        expect(setSuccessResults.length).toBe(2);
        expect(setSuccessResults[0]).toBeTruthy();
        expect(setSuccessResults[1]).toBeTruthy();
        expect(getSuccessResults.length).toBe(1);
        expect(getSuccessResults[0]).toBeTruthy();
        done();
      });
    });
  
    describe('enable, login, set preference (secure), get preference', function() {
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";
        storedKey = "language";
        storedValue = "german";

        pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          .then(function() {
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login()
          }).then(function() {
            return pinAuthFlow.getManager().setPreference(storedKey, storedValue, true)
          }).then(function() {
            setSuccessResults.push(true);
            return pinAuthFlow.getManager().getPreference(storedKey)
          }).then(function() {
            getSuccessResults.push(true);
            done();
          }).catch(done);
      });
      it('works as expected.', function(done) {
        expect(setSuccessResults.length).toBe(1);
        expect(setSuccessResults[0]).toBeTruthy();
        expect(getSuccessResults.length).toBe(1);
        expect(getSuccessResults[0]).toBeTruthy();
        done();
      });
    });

    describe('enable, login, set preference (secure), get preference - unavailable key', function() {
      var fetchedValue,
          unavailableKey = "country";
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";
        storedKey = "language";
        storedValue = "german";

        pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          .then(function() {
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login()
          }).then(function() {
            return pinAuthFlow.getManager().setPreference(storedKey, storedValue, true)
          }).then(function() {
            setSuccessResults.push(true);
            return pinAuthFlow.getManager().getPreference(unavailableKey)
          }).then(function(value) {
            getSuccessResults.push(true);
            fetchedValue = value;
            done();
          }).catch(done);
      });
      it('works as expected.', function(done) {
        expect(setSuccessResults.length).toBe(1);
        expect(setSuccessResults[0]).toBeTruthy();
        expect(getSuccessResults.length).toBe(1);
        expect(getSuccessResults[0]).toBeTruthy();
        expect(fetchedValue).toBeNull();
        done();
      });
    });

    describe('set preference (non secure), enable, login, get preference', function() {
      var fetchedValue;
      beforeEach(function(done) {
        resetTest();
        currPin = undefined;
        newPin = "1234";
        storedKey = "language";
        storedValue = "german";

        pinAuthFlow.getManager().setPreference(storedKey, storedValue, false)
          .then(function() {
            setSuccessResults.push(true);
            return pinAuthFlow.getManager().enable(localAuthTypes.PIN)
          }).then(function() {
            currPin = "1234";
            newPin = undefined;
            return pinAuthFlow.login()
          }).then(function() {
            return pinAuthFlow.getManager().getPreference(storedKey)
          }).then(function(value) {
            getSuccessResults.push(true);
            fetchedValue = value;
            done();
          }).catch(done);
      });
      it('works as expected.', function(done) {
        expect(setSuccessResults.length).toBe(1);
        expect(setSuccessResults[0]).toBeTruthy();
        expect(getSuccessResults.length).toBe(1);
        expect(getSuccessResults[0]).toBeTruthy();
        expect(fetchedValue).not.toBeNull();
        done();
      });
    });
  });
};
